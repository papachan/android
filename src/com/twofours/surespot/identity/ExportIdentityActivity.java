package com.twofours.surespot.identity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.MenuItem;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.accounts.GoogleAccountManager;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.ChildReference;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.twofours.surespot.R;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.ui.UIUtils;

public class ExportIdentityActivity extends SherlockActivity {
	private List<String> mIdentityNames;
	public static final String[] ACCOUNT_TYPE = new String[] { GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE };
	private static final String TAG = null;
	private GoogleAccountManager mAccountManager;

	private Account mAccount;
	private Drive mService;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_export_identity);

		TextView tvBlurb = (TextView) findViewById(R.id.tvHelpManualBackup);
		UIUtils.setHtml(this, tvBlurb, R.string.help_manual_backup);

		Utils.configureActionBar(this, getString(R.string.identity), getString(R.string.backup), true);
		final String identityDir = FileUtils.getIdentityExportDir().toString();

		final TextView tvPath = (TextView) findViewById(R.id.identityBackupPath);
		final Spinner spinner = (Spinner) findViewById(R.id.identitySpinner);

		final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.sherlock_spinner_item);
		adapter.setDropDownViewResource(R.layout.sherlock_spinner_dropdown_item);
		mIdentityNames = IdentityController.getIdentityNames(this);

		for (String name : mIdentityNames) {
			adapter.add(name);
		}

		spinner.setAdapter(adapter);
		spinner.setSelection(adapter.getPosition(IdentityController.getLoggedInUser()));
		spinner.setOnItemSelectedListener(new OnItemSelectedListener() {

			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

				String identityFile = identityDir + File.separator + adapter.getItem(position) + IdentityController.IDENTITY_EXTENSION;
				tvPath.setText(identityFile);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {

			}

		});

		Button exportToSdCardButton = (Button) findViewById(R.id.bExportSd);

		exportToSdCardButton.setEnabled(FileUtils.isExternalStorageMounted());

		exportToSdCardButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO progress
				final String user = (String) spinner.getSelectedItem();
				UIUtils.passwordDialog(ExportIdentityActivity.this, getString(R.string.backup_identity, user), getString(R.string.enter_password_for, user),
						new IAsyncCallback<String>() {
							@Override
							public void handleResponse(String result) {
								if (!TextUtils.isEmpty(result)) {
									exportIdentity(user, result);
								} else {
									Utils.makeToast(ExportIdentityActivity.this, getString(R.string.no_identity_exported));
								}
							}
						});

			}
		});

		Button exportToGoogleDriveButton = (Button) findViewById(R.id.bBackupDrive);

		exportToGoogleDriveButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				if (mAccountManager == null) {
					mAccountManager = new GoogleAccountManager(ExportIdentityActivity.this);
				}
				Intent accountPickerIntent = AccountPicker.newChooseAccountIntent(null, null, ACCOUNT_TYPE, false, null, null, null, null);
				startActivityForResult(accountPickerIntent, SurespotConstants.IntentRequestCodes.CHOOSE_GOOGLE_ACCOUNT);

			}
		});

	}

	// //////// Local
	private void exportIdentity(String user, String password) {
		IdentityController.exportIdentity(ExportIdentityActivity.this, user, password, new IAsyncCallback<String>() {
			@Override
			public void handleResponse(String response) {
				if (response == null) {
					Utils.makeToast(ExportIdentityActivity.this, getString(R.string.no_identity_exported));
				} else {
					Utils.makeLongToast(ExportIdentityActivity.this, response);
				}

			}
		});
	}

	// //////// DRIVE
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case SurespotConstants.IntentRequestCodes.CHOOSE_GOOGLE_ACCOUNT:
			if (data != null) {

				SurespotLog.w("Preferences", "SELECTED ACCOUNT WITH EXTRA: %s", data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
				Bundle b = data.getExtras();

				String accountName = b.getString(AccountManager.KEY_ACCOUNT_NAME);

				SurespotLog.d("Preferences", "Selected account: " + accountName);
				if (accountName != null && accountName.length() > 0) {

					mAccount = mAccountManager.getAccountByName(accountName);

					new AsyncTask<Void, Void, Void>() {

						@Override
						protected Void doInBackground(Void... params) {
							Drive drive = getDriveService();
							if (drive != null) {

								backupIdentityDrive(true);
							}
							return null;

						}

						protected void onPostExecute(Void result) {

						};

					}.execute();

					// setAccount(account);

				}
			} else {
				// mState = STATE_INITIAL;
			}
			break;
		case SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH:
			if (resultCode == Activity.RESULT_OK) {
				new AsyncTask<Void, Void, Void>() {

					@Override
					protected Void doInBackground(Void... params) {
						Drive drive = getDriveService();
						if (drive != null) {
							backupIdentityDrive(false);

						}
						return null;

					}

					protected void onPostExecute(Void result) {

					};

				}.execute();

			} else {

			}
		}
	}

	private void backupIdentityDrive(boolean firstAttempt) {
		String identityDirId = ensureDriveIdentityDirectory();
		if (identityDirId == null) {
			if (!firstAttempt) {

				this.runOnUiThread(new Runnable() {

					@Override
					public void run() {
						Utils.makeToast(ExportIdentityActivity.this, getString(R.string.could_not_backup_identity_to_google_drive));
					}
				});
			}
			return;
		}

		SurespotLog.d(TAG, "identity file id: %s", identityDirId);
		final boolean backedUp = updateIdentityDriveFile(identityDirId, "cherie", "helloooooo");
		
			this.runOnUiThread(new Runnable() {

				@Override
				public void run() {
					if (!backedUp) {
						Utils.makeToast(ExportIdentityActivity.this, getString(R.string.could_not_backup_identity_to_google_drive));
					}
					else {
						Utils.makeToast(ExportIdentityActivity.this, getString(R.string.identity_successfully_backed_up_to_google_drive));
					}
				}
			});
	
	}

	public String ensureDriveIdentityDirectory() {
		String identityDirId = null;
		try {
			// see if identities directory exists

			FileList identityDir = mService.files().list().setQ("title = 'identities' and trashed = false").execute();
			List<com.google.api.services.drive.model.File> items = identityDir.getItems();

			if (items.size() > 0) {
				for (com.google.api.services.drive.model.File file : items) {
					if (!file.getLabels().getTrashed()) {
						SurespotLog.d(TAG, "identity folder already exists");
						identityDirId = file.getId();
					}
				}
			}
			if (identityDirId == null) {
				com.google.api.services.drive.model.File file = new com.google.api.services.drive.model.File();
				file.setTitle("identities");
				file.setMimeType(SurespotConstants.MimeTypes.DRIVE_FOLDER);

				com.google.api.services.drive.model.File insertedFile = mService.files().insert(file).execute();

				identityDirId = insertedFile.getId();

			}

		} catch (UserRecoverableAuthIOException e) {
			startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
		} catch (IOException e) {
			SurespotLog.w(TAG, e, "createDriveIdentityDirectory");
		}
		return identityDirId;
	}

	public boolean updateIdentityDriveFile(String idDirId, String username, String encryptedIdentity) {
		try {
			String filename = username + IdentityController.IDENTITY_EXTENSION;

			// see if identity exists
			com.google.api.services.drive.model.File file = null;
			ChildReference idFile = getIdentityFile(idDirId, username);
			if (idFile != null) {

				// update
				file = mService.files().get(idFile.getId()).execute();
				if (file != null && !file.getLabels().getTrashed()) {
					SurespotLog.d(TAG, "updateIdentityDriveFile, updating existing identity file: %s", filename);
					ByteArrayContent content = new ByteArrayContent("text/plain", "your mama".getBytes());
					mService.files().update(file.getId(), file, content).setNewRevision(false).execute();
					return true;
				}

			}

			// create
			SurespotLog.d(TAG, "updateIdentityDriveFile, inserting new identity file: %s", filename);

			file = new com.google.api.services.drive.model.File();
			ParentReference pr = new ParentReference();
			pr.setId(idDirId);
			ArrayList<ParentReference> parent = new ArrayList<ParentReference>(1);
			parent.add(pr);
			file.setParents(parent);
			file.setTitle(filename);

			file.setMimeType(SurespotConstants.MimeTypes.TEXT);

			ByteArrayContent content = new ByteArrayContent("text/plain", encryptedIdentity.getBytes());

			com.google.api.services.drive.model.File insertedFile = mService.files().insert(file, content).execute();
			return true;

		} catch (UserRecoverableAuthIOException e) {
			startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
		} catch (IOException e) {
			SurespotLog.w(TAG, e, "updateIdentityDriveFile");
		}
		return false;
	}

	private ChildList getIdentityFiles(String identityDirId) {
		ChildList identityFileList = null;
		try {
			identityFileList = mService.children().list(identityDirId).execute();

		} catch (IOException e1) {
			SurespotLog.w(TAG, e1, "listIdentityFiles");
		}
		return identityFileList;
	}

	private ChildReference getIdentityFile(String identityDirId, String username) throws IOException {
		ChildReference idFile = null;

		// "title = '" + username + "'";
		ChildList identityFileList = mService.children().list(identityDirId)
				.setQ("title='" + username + IdentityController.IDENTITY_EXTENSION + "' and trashed = false").execute();
		List<ChildReference> items = identityFileList.getItems();

		if (items.size() == 1) {
			SurespotLog.d(TAG, "getIdentityFile, found identity file for: %s", username);
			idFile = items.get(0);
			// for (ChildReference file : items) {
			// if (!file.getLabels().getTrashed()) {
			// SurespotLog.d(TAG, "identity folder already exists");
			// identityDirId = file.getId();
			// }
			// }
		} else {
			if (items.size() > 1) {
				// delete all but one identity...should never happen
				SurespotLog.w(TAG, "$d identities with the same filename found on google drive: %s", items.size(), username);

				for (int i = items.size(); i > 0; i--) {
					SurespotLog.w(TAG, "deleting identity file from google drive %s", username);
					mService.files().delete(items.get(i-1).getId()).execute();
				}
				idFile = items.get(0);
			}
		}

		return idFile;
	}

	private Drive getDriveService() {

		if (mService == null) {

			GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(this, DriveScopes.DRIVE);
			credential.setSelectedAccountName(mAccount.name);
			mService = new Drive.Builder(AndroidHttp.newCompatibleTransport(), new GsonFactory(), credential).build();

		}
		return mService;

	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			finish();

			return true;
		default:
			return super.onOptionsItemSelected(item);
		}

	}

}
