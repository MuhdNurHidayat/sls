/**
 *  This file is part of A Simple Last.fm Scrobbler.
 *
 *  A Simple Last.fm Scrobbler is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  A Simple Last.fm Scrobbler is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with A Simple Last.fm Scrobbler.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  See http://code.google.com/p/a-simple-lastfm-scrobbler/ for the latest version.
 */

package com.adam.aslfms.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.adam.aslfms.AppSettings;
import com.adam.aslfms.ScrobblesDbAdapter;
import com.adam.aslfms.Status;
import com.adam.aslfms.Track;
import com.adam.aslfms.Status.BadAuthException;
import com.adam.aslfms.Status.BadSessionException;
import com.adam.aslfms.Status.FailureException;
import com.adam.aslfms.Status.TemporaryFailureException;
import com.adam.aslfms.service.Handshaker.HandshakeInfo;

/**
 * NetworkLoop does all the network requests - handshaking, scrobbling and
 * notifying now playing. These requests are initiated through the launch
 * methods: launchHandshaker(), launchClearCreds, launchScrobbler and
 * launchNPNotifier
 * 
 * FIXME: this class is somewhat bloated and difficult to maintain
 * @author tgwizard
 * 
 */
public class NetworkLoop implements Runnable {

	private static final String TAG = "NetLoop";

	private final Context mCtx;
	private final AppSettings settings;
	private final ScrobblesDbAdapter mDbHelper;

	private static final Object waitNotifyObject = new Object();
	private boolean mWait;

	private Scrobbler mScrobbler;
	private NPNotifier mNPNotifier;
	private Handshaker mHandshaker;

	private int mRetryCount = 0;

	private boolean mDoSleepRetry = false;
	// in milliseconds
	private long mSleepRetryTime = 0;

	// handshake-requests
	private boolean mHandshakeRequests = false;
	private boolean mDoAuth = false;

	// scrobble-requests
	private int mScrobbleRequests = 0;

	// notifynp-requests
	private Track mNotifyNPTrack = null;

	public NetworkLoop(Context ctx, ScrobblesDbAdapter dbHelper) {
		super();
		this.mCtx = ctx;
		this.settings = new AppSettings(ctx);
		this.mDbHelper = dbHelper;
	}

	@Override
	public void run() {
		mHandshaker = new Handshaker(mCtx);
		mScrobbler = null;
		mNPNotifier = null;
		while (true) {
			synchronized (waitNotifyObject) {

				mWait = true;

				if (wannaHandshake()) {
					mWait = false;
				} else if (!doSleep() && (wannaScrobble() || wannaNotifyNP())) {
					mWait = false;
				}

				while (mWait) {
					try {
						if (doSleep()) {
							Log.d(TAG, "Will sleep for: " + getSleepTime());
							waitNotifyObject.wait(getSleepTime());
							// this is so that when we stop sleeping, we break
							// the sleep-loop.
							mWait = false;
							launchHandshaker(false);
						} else {
							waitNotifyObject.wait();
						}
					} catch (InterruptedException e) {
						Log.i(TAG, "Got interrupted while waiting");
						Log.i(TAG, e.getMessage());
					}
				}
			}

			// handshaking
			boolean doHand = false;
			boolean doAuth = false;
			synchronized (this) {
				if (wannaHandshake()) {
					doHand = true;
					doAuth = mDoAuth;
					// reset
					mHandshakeRequests = false;
					mDoAuth = false;
				}
			}

			if (doHand) {
				if (!doHandshake(doAuth)) {
					Log.d(TAG, "doHandshake() failed, re-continuing loop");
					continue;
				}
			}

			// scrobbling
			boolean doScrobble = false;
			int sCount = 0;
			synchronized (this) {
				if (wannaScrobble()) {
					if (mScrobbler == null) {
						launchHandshaker(false);
					} else {
						doScrobble = true;
						sCount = getNScrobbleReqs();
					}
				}
			}

			if (doScrobble) {
				doScrobble(sCount);
			}

			// np-notifying
			boolean doNotifyNP = false;
			Track track = null;
			synchronized (this) {
				if (wannaNotifyNP()) {
					if (mNPNotifier == null) {
						launchHandshaker(false);
					} else {
						doNotifyNP = true;
						track = popAndResetNPTrack();
					}
				}
			}

			if (doNotifyNP) {
				doNotifyNP(track);
			}
		}
	}

	private boolean doHandshake(boolean doAuth) {
		boolean ret = false;

		mScrobbler = null;
		mNPNotifier = null;

		if (doAuth)
			updateAuthStatus(Status.AUTHSTATUS_UPDATING);

		try {
			HandshakeInfo hi = mHandshaker.handshake();

			mScrobbler = new Scrobbler(mCtx, hi, mDbHelper);
			mNPNotifier = new NPNotifier(mCtx, hi);

			resetRetry();

			// we don't need it anymore, settings.getPwdMd5() is enough
			settings.setPassword("");

			updateAuthStatus(Status.AUTHSTATUS_OK);

			// won't do anything if there aren't any scrobbles,
			// but will submit those tracks that were prepared
			// but interrupted by a badauth
			launchScrobbler();

			ret = true;
		} catch (BadAuthException e) {
			if (doAuth)
				updateAuthStatus(Status.AUTHSTATUS_BADAUTH);
			else {
				// this should mean that the user called launchClearCreds, and
				// that
				// all user information is gone
				updateAuthStatus(Status.AUTHSTATUS_NOAUTH);
			}
			// badauth means we cant do any scrobbling/notifying, so clear them
			// the scrobbles already prepared will be sent at a later time
			unsetScrobblingAndNPNotifying();
		} catch (TemporaryFailureException e) {
			sleepRetry();
			if (doAuth)
				updateAuthStatus(Status.AUTHSTATUS_RETRYLATER);
		} catch (FailureException e) {
			Log.e(TAG, "Serious failure while handshaking");
			Log.e(TAG, e.getMessage());
			updateAuthStatus(Status.AUTHSTATUS_FAILED);
			// TODO: what??
			// this is a _serious_ failure
		}
		return ret;
	}

	private boolean doScrobble(int sCount) {
		boolean ret = false;
		if (mScrobbler == null) {
			Log.e(TAG, "Scrobbler is null when we want to scrobble!!");
		} else {
			try {
				boolean doAgain = mScrobbler.scrobbleCommit();
				if (!doAgain) {
					decScrobbleReqs(sCount);
				}
				ret = true;
			} catch (BadSessionException e) {
				Log.i(TAG, e.getMessage());
				launchHandshaker(false);
			} catch (TemporaryFailureException e) {
				Log.i(TAG, e.getMessage());
				retry();
			} catch (FailureException e) {
				Log.e(TAG, "Serious failure while scrobbling");
				Log.e(TAG, e.getMessage());
				// TODO: what??
				// this is a _serious_ failure
			}
		}
		return ret;
	}

	private boolean doNotifyNP(Track t) {
		boolean ret = false;
		if (mNPNotifier == null) {
			Log.e(TAG, "npNotifier is null when we want to notify-np!!");
		} else {
			try {
				mNPNotifier.notifyNowPlaying(t);
			} catch (BadSessionException e) {
				Log.i(TAG, e.getMessage());
				launchHandshaker(false);
				launchNPNotifier(t);
			} catch (TemporaryFailureException e) {
				Log.i(TAG, e.getMessage());
				retry();
				launchNPNotifier(t);
			} catch (FailureException e) {
				Log.e(TAG, "Serious failure while notifying np");
				Log.e(TAG, e.getMessage());
				// TODO: what??
				// this is a _serious_ failure
			}
		}
		return ret;
	}

	/**
	 * @return whether the loop should sleep for a certain time instead of
	 *         waiting indefinitely
	 */
	private boolean doSleep() {
		return mDoSleepRetry;
	}

	/**
	 * @return the time to sleep in milliseconds
	 */
	private long getSleepTime() {
		return mSleepRetryTime;
	}

	/**
	 * Ask to sleep instead of just waiting, and increase the sleep time. Call
	 * when handshake fails due to non-badauth situations.
	 */
	private void sleepRetry() {
		mDoSleepRetry = true;

		// TODO: change to correct way
		mSleepRetryTime += 5000;
		Log.i(TAG, "Will do sleep retry, sleeping: " + mSleepRetryTime + "s");
	}

	/**
	 * Say that the current action (scrobble/np-notification) failed, and that
	 * it should be retried. The action has to be reset elsewhere.
	 */
	private void retry() {
		mRetryCount++;
		if (mRetryCount > 3) {
			launchHandshaker(false);
		}
	}

	/**
	 * Reset retry counts and sleep counts. To be called after a successful
	 * handshake.
	 */
	private void resetRetry() {
		mRetryCount = 0;
		mDoSleepRetry = false;
		mSleepRetryTime = 0;
	}

	/**
	 * Requests that a the loop should be awaken if it is sleeping/waiting. If
	 * none of the methods wannaHandshake(), wannaScrobble() and wannaNotifyNP()
	 * returns true, this will just cause the loop to go one iteration, and then
	 * back to waiting/sleeping.
	 */
	private void requestResume() {
		synchronized (waitNotifyObject) {
			mWait = false;
			waitNotifyObject.notifyAll();
		}
	}

	/**
	 * Asks the loop to do a handshake request/first time authentication of user
	 * credentials.
	 * 
	 * @param auth
	 *            true means this is a first time authentication, false
	 *            otherwise
	 */
	public void launchHandshaker(boolean auth) {
		synchronized (this) {
			mHandshakeRequests = true;
			mDoAuth = mDoAuth || auth;
		}
		requestResume();
	}

	/**
	 * Tells the loop that the user credentials has been cleared, and it should
	 * stop.
	 */
	public void launchClearCreds() {
		launchHandshaker(false);
		// TODO: this will still show "Wrong username/password" if handshaker
		// already is retrying
	}

	/**
	 * Asks the loop to scrobble tracks in the db.
	 */
	public void launchScrobbler() {
		synchronized (this) {
			mScrobbleRequests++;
		}
		requestResume();
	}

	/**
	 * Asks the loop to make a now playing notification of Track t.
	 * 
	 * @param t
	 *            The track in question
	 */
	public void launchNPNotifier(Track t) {
		synchronized (this) {
			if (mNotifyNPTrack == null
					|| t.getWhen() > mNotifyNPTrack.getWhen()) {
				mNotifyNPTrack = t;
			}
		}
		requestResume();
	}

	private synchronized boolean wannaHandshake() {
		return mHandshakeRequests;
	}

	private synchronized boolean wannaScrobble() {
		return mScrobbleRequests > 0;
	}

	private synchronized boolean wannaNotifyNP() {
		return mNotifyNPTrack != null;
	}

	private synchronized Track popAndResetNPTrack() {
		Track ret = mNotifyNPTrack;
		mNotifyNPTrack = null;
		return ret;
	}

	private synchronized int getNScrobbleReqs() {
		return mScrobbleRequests;
	}

	private synchronized void decScrobbleReqs(int a) {
		mScrobbleRequests -= a;
	}

	private synchronized void unsetScrobblingAndNPNotifying() {
		mScrobbleRequests = 0;
		mNotifyNPTrack = null;
	}

	private void updateAuthStatus(int st) {
		Log.d(TAG, "updateAS: " + st);
		settings.setAuthStatus(st);
		Intent i = new Intent(ScrobblingService.BROADCAST_ONAUTHCHANGED);
		mCtx.sendBroadcast(i);
	}

}
