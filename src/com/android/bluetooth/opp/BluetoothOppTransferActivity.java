/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import android.app.NotificationManager;
import android.bluetooth.AlertActivity;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.android.bluetooth.R;

/**
 * Handle all transfer related dialogs: -Ongoing transfer -Receiving one file
 * dialog -Sending one file dialog -sending multiple files dialog -Complete
 * transfer -receive -receive success, will trigger corresponding handler
 * -receive fail dialog -send -send success dialog -send fail dialog -Other
 * dialogs - - DIALOG_RECEIVE_ONGOING will transition to
 * DIALOG_RECEIVE_COMPLETE_SUCCESS or DIALOG_RECEIVE_COMPLETE_FAIL
 * DIALOG_SEND_ONGOING will transition to DIALOG_SEND_COMPLETE_SUCCESS or
 * DIALOG_SEND_COMPLETE_FAIL
 */
public class BluetoothOppTransferActivity extends AlertActivity
        implements DialogInterface.OnClickListener {
    private static final String TAG = "BluetoothOppTransferActivity";
    private static final boolean D = Constants.DEBUG;
    private static final boolean V = Constants.VERBOSE;

    private Uri mUri;

    // ongoing transfer-0 complete transfer-1
    boolean mIsComplete;

    private BluetoothOppTransferInfo mTransInfo;

    private ProgressBar mProgressTransfer;

    private TextView mPercentView;

    private View mView = null;

    private TextView mLine1Title, mLine2Title, mLine3Title, mLine4Title;

    private TextView mLine1View, mLine2View, mLine3View, mLine4View, mLine5View;

    private int mWhichDialog;

    private BluetoothAdapter mAdapter;

    // Dialogs definition:
    // Receive progress dialog
    public static final int DIALOG_RECEIVE_ONGOING = 0;

    // Receive complete and success dialog
    public static final int DIALOG_RECEIVE_COMPLETE_SUCCESS = 1;

    // Receive complete and fail dialog: will display some fail reason
    public static final int DIALOG_RECEIVE_COMPLETE_FAIL = 2;

    // Send progress dialog
    public static final int DIALOG_SEND_ONGOING = 3;

    // Send complete and success dialog
    public static final int DIALOG_SEND_COMPLETE_SUCCESS = 4;

    // Send complete and fail dialog: will let user retry
    public static final int DIALOG_SEND_COMPLETE_FAIL = 5;

    /** Observer to get notified when the content observer's data changes */
    private BluetoothTransferContentObserver mObserver;

    // do not update button during activity creating, only update when db
    // changes after activity created
    private boolean mNeedUpdateButton = false;

    private class BluetoothTransferContentObserver extends ContentObserver {
        BluetoothTransferContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            if (V) {
                Log.v(TAG, "received db changes.");
            }
            mNeedUpdateButton = true;
            updateProgressbar();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mUri = intent.getData();

        mTransInfo = new BluetoothOppTransferInfo();
        mTransInfo = BluetoothOppUtility.queryRecord(this, mUri);
        if (mTransInfo == null) {
            if (V) {
                Log.e(TAG, "Error: Can not get data from db");
            }
            finish();
            return;
        }

        mIsComplete = BluetoothShare.isStatusCompleted(mTransInfo.mStatus);

        displayWhichDialog();

        // update progress bar for ongoing transfer
        if (!mIsComplete) {
            mObserver = new BluetoothTransferContentObserver();
            getContentResolver().registerContentObserver(BluetoothShare.CONTENT_URI, true,
                    mObserver);
        }

        if (mWhichDialog != DIALOG_SEND_ONGOING && mWhichDialog != DIALOG_RECEIVE_ONGOING) {
            // set this record to INVISIBLE
            BluetoothOppUtility.updateVisibilityToHidden(this, mUri);
        }

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        // Set up the "dialog"
        setUpDialog();
    }

    @Override
    protected void onDestroy() {
        if (D) {
            Log.d(TAG, "onDestroy()");
        }

        if (mObserver != null) {
            getContentResolver().unregisterContentObserver(mObserver);
        }
        super.onDestroy();
    }

    private void displayWhichDialog() {
        int direction = mTransInfo.mDirection;
        boolean isSuccess = BluetoothShare.isStatusSuccess(mTransInfo.mStatus);
        boolean isComplete = BluetoothShare.isStatusCompleted(mTransInfo.mStatus);

        if (direction == BluetoothShare.DIRECTION_INBOUND) {
            if (isComplete) {
                if (isSuccess) {
                    // should not go here
                    mWhichDialog = DIALOG_RECEIVE_COMPLETE_SUCCESS;
                } else if (!isSuccess) {
                    mWhichDialog = DIALOG_RECEIVE_COMPLETE_FAIL;
                }
            } else if (!isComplete) {
                mWhichDialog = DIALOG_RECEIVE_ONGOING;
            }
        } else if (direction == BluetoothShare.DIRECTION_OUTBOUND) {
            if (isComplete) {
                if (isSuccess) {
                    mWhichDialog = DIALOG_SEND_COMPLETE_SUCCESS;

                } else if (!isSuccess) {
                    mWhichDialog = DIALOG_SEND_COMPLETE_FAIL;
                }
            } else if (!isComplete) {
                mWhichDialog = DIALOG_SEND_ONGOING;
            }
        }

        if (V) {
            Log.v(TAG, " WhichDialog/dir/isComplete/failOrSuccess" + mWhichDialog + direction
                    + isComplete + isSuccess);
        }
    }

    private void setUpDialog() {
        mAlertBuilder.setTitle(getString(R.string.download_title));
        if ((mWhichDialog == DIALOG_RECEIVE_ONGOING) || (mWhichDialog == DIALOG_SEND_ONGOING)) {
            mAlertBuilder.setPositiveButton(R.string.download_ok, this);
            mAlertBuilder.setNegativeButton(R.string.download_cancel, this);
        } else if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_SUCCESS) {
            mAlertBuilder.setPositiveButton(R.string.download_succ_ok, this);
        } else if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_FAIL) {
            mAlertBuilder.setIconAttribute(android.R.attr.alertDialogIcon);
            mAlertBuilder.setPositiveButton(R.string.download_fail_ok, this);
        } else if (mWhichDialog == DIALOG_SEND_COMPLETE_SUCCESS) {
            mAlertBuilder.setPositiveButton(R.string.upload_succ_ok, this);
        } else if (mWhichDialog == DIALOG_SEND_COMPLETE_FAIL) {
            mAlertBuilder.setIconAttribute(android.R.attr.alertDialogIcon);
            mAlertBuilder.setNegativeButton(R.string.upload_fail_cancel, this);
        }
        mAlertBuilder.setView(createView());
        setupAlert();
    }

    private View createView() {

        mView = getLayoutInflater().inflate(R.layout.file_transfer, null);

        mProgressTransfer = (ProgressBar) mView.findViewById(R.id.progress_transfer);
        mPercentView = (TextView) mView.findViewById(R.id.progress_percent);

        customizeViewContent();

        // no need update button when activity creating
        mNeedUpdateButton = false;
        updateProgressbar();

        return mView;
    }

    /**
     * customize the content of view
     */
    private void customizeViewContent() {
        String tmp;

        mLine1Title = (TextView) mView.findViewById(R.id.line1_title);
        mLine2Title = (TextView) mView.findViewById(R.id.line2_title);
        mLine3Title = (TextView) mView.findViewById(R.id.line3_title);
        mLine4Title = (TextView) mView.findViewById(R.id.line4_title);
        mLine1View = (TextView) mView.findViewById(R.id.line1_view);
        mLine2View = (TextView) mView.findViewById(R.id.line2_view);
        mLine3View = (TextView) mView.findViewById(R.id.line3_view);
        mLine4View = (TextView) mView.findViewById(R.id.line4_view);
        mLine5View = (TextView) mView.findViewById(R.id.line5_view);

        if (mWhichDialog == DIALOG_RECEIVE_ONGOING
                || mWhichDialog == DIALOG_RECEIVE_COMPLETE_SUCCESS) {
            mLine1Title.setText(R.string.bt_transfer_receive_from);
            mLine1View.setText(mTransInfo.mDeviceName);
            mLine2Title.setText(R.string.bt_transfer_file_name);
            mLine2View.setText(mTransInfo.mFileName);
            mLine3Title.setText(R.string.bt_transfer_file_size);
            mLine3View.setText(Formatter.formatFileSize(this, mTransInfo.mTotalBytes));
            mLine4Title.setVisibility(View.GONE);
            mLine4View.setVisibility(View.GONE);
            if (mWhichDialog == DIALOG_RECEIVE_ONGOING) {
                mLine5View.setText(R.string.download_line5);
            } else if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_SUCCESS) {
                mLine5View.setText(R.string.download_succ_line5);
            }
        } else if (mWhichDialog == DIALOG_SEND_ONGOING
                || mWhichDialog == DIALOG_SEND_COMPLETE_SUCCESS) {
            mLine1Title.setText(R.string.bt_transfer_send_to);
            mLine1View.setText(mTransInfo.mDeviceName);
            mLine2Title.setText(R.string.bt_transfer_file_name);
            mLine2View.setText(mTransInfo.mFileName);
            mLine3Title.setText(R.string.bt_transfer_file_type);
            mLine3View.setText(mTransInfo.mFileType);
            mLine4Title.setText(R.string.bt_transfer_file_size);
            mLine4Title.setVisibility(View.VISIBLE);
            mLine4View.setText(Formatter.formatFileSize(this, mTransInfo.mTotalBytes));
            mLine4View.setVisibility(View.VISIBLE);
            if (mWhichDialog == DIALOG_SEND_ONGOING) {
                mLine5View.setText(R.string.upload_line5);
            } else if (mWhichDialog == DIALOG_SEND_COMPLETE_SUCCESS) {
                mLine5View.setText(R.string.upload_succ_line5);
            }
        } else if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_FAIL) {
            if (mTransInfo.mStatus == BluetoothShare.STATUS_ERROR_SDCARD_FULL) {
                int id = BluetoothOppUtility.deviceHasNoSdCard()
                        ? R.string.bt_sm_2_1_nosdcard
                        : R.string.bt_sm_2_1_default;
                mLine1View.setText(id);
                mLine2Title.setText(R.string.bt_transfer_file_name);
                mLine2View.setText(mTransInfo.mFileName);
                mLine3Title.setText(R.string.bt_transfer_space_needed);
                mLine3View.setText(Formatter.formatFileSize(this, mTransInfo.mTotalBytes));
            } else {
                mLine1View.setText(R.string.download_fail_line1);
                mLine2Title.setText(R.string.bt_transfer_file_name);
                mLine2View.setText(mTransInfo.mFileName);
                mLine3Title.setText(R.string.bt_transfer_failed_reason);
                mLine3View.setText(BluetoothOppUtility.getStatusDescription(this, mTransInfo.mStatus,
                        mTransInfo.mDeviceName));
            }
            mLine1Title.setVisibility(View.INVISIBLE);
            mLine4Title.setVisibility(View.GONE);
            mLine4View.setVisibility(View.GONE);
            mLine5View.setVisibility(View.GONE);
        } else if (mWhichDialog == DIALOG_SEND_COMPLETE_FAIL) {
            mLine1View.setText(getString(R.string.upload_fail_line1, mTransInfo.mDeviceName));
            mLine2Title.setText(R.string.bt_transfer_file_name);
            mLine2View.setText(mTransInfo.mFileName);
            mLine3Title.setText(R.string.bt_transfer_failed_reason);
            mLine3View.setText(BluetoothOppUtility.getStatusDescription(this, mTransInfo.mStatus,
                    mTransInfo.mDeviceName));
            mLine1Title.setVisibility(View.INVISIBLE);
            mLine4Title.setVisibility(View.GONE);
            mLine4View.setVisibility(View.GONE);
            mLine5View.setVisibility(View.GONE);
        }

        if (BluetoothShare.isStatusError(mTransInfo.mStatus)) {
            mProgressTransfer.setVisibility(View.GONE);
            mPercentView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_SUCCESS) {
                    // "Open" - open receive file
                    BluetoothOppUtility.openReceivedFile(this, mTransInfo.mFileName,
                            mTransInfo.mFileType, mTransInfo.mTimeStamp, mUri);

                    // make current transfer "hidden"
                    BluetoothOppUtility.updateVisibilityToHidden(this, mUri);

                    // clear correspondent notification item
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(
                            mTransInfo.mID);
                } else if (mWhichDialog == DIALOG_SEND_COMPLETE_SUCCESS) {
                    BluetoothOppUtility.updateVisibilityToHidden(this, mUri);
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(
                            mTransInfo.mID);
                }
                break;

            case DialogInterface.BUTTON_NEGATIVE:
                if (mWhichDialog == DIALOG_RECEIVE_ONGOING || mWhichDialog == DIALOG_SEND_ONGOING) {
                    // "Stop" button
                    this.getContentResolver().delete(mUri, null, null);

                    String msg = "";
                    if (mWhichDialog == DIALOG_RECEIVE_ONGOING) {
                        msg = getString(R.string.bt_toast_3, mTransInfo.mDeviceName);
                    } else if (mWhichDialog == DIALOG_SEND_ONGOING) {
                        msg = getString(R.string.bt_toast_6, mTransInfo.mDeviceName);
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(
                            mTransInfo.mID);
                } else if (mWhichDialog == DIALOG_SEND_COMPLETE_FAIL) {

                    BluetoothOppUtility.updateVisibilityToHidden(this, mUri);
                }
                break;
        }
        finish();
    }

    /**
     * Update progress bar per data got from content provider
     */
    private void updateProgressbar() {
        mTransInfo = BluetoothOppUtility.queryRecord(this, mUri);
        if (mTransInfo == null) {
            if (V) {
                Log.e(TAG, "Error: Can not get data from db");
            }
            return;
        }

        // Set Transfer Max as 100. Percentage calculation would be done in setProgress API
        mProgressTransfer.setMax(100);

        if (mTransInfo.mTotalBytes != 0) {
            if (V) {
                Log.v(TAG, "mCurrentBytes: " + mTransInfo.mCurrentBytes + " mTotalBytes: "
                        + mTransInfo.mTotalBytes + " (" + (int) ((mTransInfo.mCurrentBytes * 100)
                        / mTransInfo.mTotalBytes) + "%)");
            }
            mProgressTransfer.setProgress(
                    (int) ((mTransInfo.mCurrentBytes * 100) / mTransInfo.mTotalBytes));
        } else {
            mProgressTransfer.setProgress(100);
        }

        mPercentView.setText(BluetoothOppUtility.formatProgressText(mTransInfo.mTotalBytes,
                mTransInfo.mCurrentBytes));

        // Handle the case when DIALOG_RECEIVE_ONGOING evolve to
        // DIALOG_RECEIVE_COMPLETE_SUCCESS/DIALOG_RECEIVE_COMPLETE_FAIL
        // Handle the case when DIALOG_SEND_ONGOING evolve to
        // DIALOG_SEND_COMPLETE_SUCCESS/DIALOG_SEND_COMPLETE_FAIL
        if (!mIsComplete && BluetoothShare.isStatusCompleted(mTransInfo.mStatus)
                && mNeedUpdateButton) {
            if (mObserver != null) {
                getContentResolver().unregisterContentObserver(mObserver);
                mObserver = null;
            }
            displayWhichDialog();
            updateButton();
            customizeViewContent();
        }
    }

    /**
     * Update button when one transfer goto complete from ongoing
     */
    private void updateButton() {
        if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_SUCCESS) {
            changeButtonVisibility(DialogInterface.BUTTON_NEGATIVE, View.GONE);
            changeButtonText(
                    DialogInterface.BUTTON_POSITIVE,
                    getString(R.string.download_succ_ok));
        } else if (mWhichDialog == DIALOG_RECEIVE_COMPLETE_FAIL) {
            changeIconAttribute(android.R.attr.alertDialogIcon);
            changeButtonVisibility(DialogInterface.BUTTON_NEGATIVE, View.GONE);
            changeButtonText(
                    DialogInterface.BUTTON_POSITIVE,
                    getString(R.string.download_fail_ok));
        } else if (mWhichDialog == DIALOG_SEND_COMPLETE_SUCCESS) {
            changeButtonVisibility(DialogInterface.BUTTON_NEGATIVE, View.GONE);
            changeButtonText(
                    DialogInterface.BUTTON_POSITIVE,
                    getString(R.string.upload_succ_ok));
        } else if (mWhichDialog == DIALOG_SEND_COMPLETE_FAIL) {
            changeIconAttribute(android.R.attr.alertDialogIcon);
            changeButtonText(
                    DialogInterface.BUTTON_NEGATIVE,
                    getString(R.string.upload_fail_cancel));
        }
    }
}
