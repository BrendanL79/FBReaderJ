/*
 * Copyright (C) 2010-2011 Geometer Plus <contact@geometerplus.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader.network;

import java.util.*;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;

import org.geometerplus.zlibrary.core.network.ZLNetworkException;
import org.geometerplus.zlibrary.core.resources.ZLResource;

import org.geometerplus.fbreader.network.*;
import org.geometerplus.fbreader.network.authentication.NetworkAuthenticationManager;
import org.geometerplus.fbreader.network.authentication.litres.LitResAuthenticationManager;
import org.geometerplus.fbreader.network.tree.NetworkBookTree;
import org.geometerplus.fbreader.network.urlInfo.UrlInfo;

import org.geometerplus.android.util.UIUtil;
import org.geometerplus.android.util.PackageUtil;

abstract class Util implements UserRegistrationConstants {
	private static final String REGISTRATION_ACTION =
		"android.fbreader.action.NETWORK_LIBRARY_REGISTER";
	static final String TOPUP_ACTION =
		"android.fbreader.action.network.TOPUP";
	static final String LIST_ACTION_ID = "list";
	static final String BROWSER_ACTION_ID = "browser";
	static final String SMS_ACTION_ID = "sms";
	static final String CREDIT_CARD_ACTION_ID = "credit_card";
	static final String SELF_SERVICE_ACTION_ID = "self_service";

	private static boolean testService(Activity activity, String action, String url) {
		return url != null && PackageUtil.canBeStarted(activity, new Intent(action, Uri.parse(url)), true);
	}

	static boolean isRegistrationSupported(Activity activity, INetworkLink link) {
		return testService(
			activity,
			REGISTRATION_ACTION,
			link.getUrl(UrlInfo.Type.SignUp)
		);
	}

	static void runRegistrationDialog(Activity activity, INetworkLink link) {
		try {
			final Intent intent = new Intent(
				REGISTRATION_ACTION,
				Uri.parse(link.getUrl(UrlInfo.Type.SignUp))
			);
			if (PackageUtil.canBeStarted(activity, intent, true)) {
				activity.startActivityForResult(new Intent(
					REGISTRATION_ACTION,
					Uri.parse(link.getUrl(UrlInfo.Type.SignUp))
				), NetworkBaseActivity.SIGNUP_CODE);
			}
		} catch (ActivityNotFoundException e) {
		}
	}

	private static final Map<Activity,Runnable> ourAfterRegisrationMap =
		new HashMap<Activity,Runnable>();

	static void runAuthenticationDialog(Activity activity, INetworkLink link, String error, Runnable onSuccess) {
		final NetworkAuthenticationManager mgr = link.authenticationManager();

		final Intent intent = new Intent(activity, AuthenticationActivity.class);
		intent.putExtra(AuthenticationActivity.USERNAME_KEY, mgr.UserNameOption.getValue());
		if (isRegistrationSupported(activity, link)) {
			intent.putExtra(AuthenticationActivity.SHOW_SIGNUP_LINK_KEY, true);
		}
		intent.putExtra(AuthenticationActivity.SCHEME_KEY, "https");
		intent.putExtra(AuthenticationActivity.ERROR_KEY, error);
		if (onSuccess != null) {
			ourAfterRegisrationMap.put(activity, onSuccess);
		}
		activity.startActivityForResult(intent, NetworkBaseActivity.CUSTOM_AUTHENTICATION_CODE);
	}

	static void processCustomAuthentication(final Activity activity, final INetworkLink link, int resultCode, Intent data) {
		final Runnable onSuccess = ourAfterRegisrationMap.get(activity);
		ourAfterRegisrationMap.remove(activity);
		switch (resultCode) {
			case AuthenticationActivity.RESULT_CANCELED:
				UIUtil.wait(
					"signOut",
					new Runnable() {
						public void run() {
							final NetworkAuthenticationManager mgr =
								 link.authenticationManager();
							if (mgr.mayBeAuthorised(false)) {
								mgr.logOut();
							}
							final NetworkLibrary library = NetworkLibrary.Instance();
							library.invalidateVisibility();
							library.synchronize();
							NetworkView.Instance().fireModelChanged();
						}
					},
					activity
				);
				break;
			case AuthenticationActivity.RESULT_OK:
			{
				final ZLResource resource =
					ZLResource.resource("dialog").getResource("AuthenticationDialog");
				final String username =
					data.getStringExtra(AuthenticationActivity.USERNAME_KEY);
				final String password =
					data.getStringExtra(AuthenticationActivity.PASSWORD_KEY);
				if (username.length() == 0) {
					runAuthenticationDialog(
						activity, link,
						resource.getResource("loginIsEmpty").getValue(),
						onSuccess
					);
				}
				final NetworkAuthenticationManager mgr = link.authenticationManager();
				mgr.UserNameOption.setValue(username);
				final Runnable runnable = new Runnable() {
					public void run() {
						try {
							mgr.authorise(password);
							if (mgr.needsInitialization()) {
								mgr.initialize();
							}
							if (onSuccess != null) {
								onSuccess.run();
							}
						} catch (ZLNetworkException e) {
							mgr.logOut();
							runAuthenticationDialog(activity, link, e.getMessage(), onSuccess);
							return;
						}
						final NetworkLibrary library = NetworkLibrary.Instance();
						library.invalidateVisibility();
						library.synchronize();
						NetworkView.Instance().fireModelChanged();
					}
				};
				UIUtil.wait("authentication", runnable, activity);
				break;
			}
			case AuthenticationActivity.RESULT_SIGNUP:
				Util.runRegistrationDialog(activity, link);
				break;
		}
	}

	static void processSignup(INetworkLink link, int resultCode, Intent data) {
		if (resultCode == Activity.RESULT_OK && data != null) {
			try {
				final NetworkAuthenticationManager mgr = link.authenticationManager();
				if (mgr instanceof LitResAuthenticationManager) {
					((LitResAuthenticationManager)mgr).initUser(
						data.getStringExtra(USER_REGISTRATION_USERNAME),
						data.getStringExtra(USER_REGISTRATION_LITRES_SID),
						"",
						false
					);
				}
				if (!mgr.isAuthorised(true)) {
					throw new ZLNetworkException(NetworkException.ERROR_AUTHENTICATION_FAILED);
				}
				try {
					mgr.initialize();
				} catch (ZLNetworkException e) {
					mgr.logOut();
					throw e;
				}
			} catch (ZLNetworkException e) {
				// TODO: show an error message
			}
		}
	}

	static boolean isTopupSupported(Activity activity, INetworkLink link) {
		return
			isTopupSupported(activity, link, BROWSER_ACTION_ID) ||
			isTopupSupported(activity, link, SMS_ACTION_ID) ||
			isTopupSupported(activity, link, CREDIT_CARD_ACTION_ID) ||
			isTopupSupported(activity, link, SELF_SERVICE_ACTION_ID);
	}

	static boolean isTopupSupported(Activity activity, INetworkLink link, String actionId) {
		if (BROWSER_ACTION_ID.equals(actionId)) {
			return link.getUrl(UrlInfo.Type.TopUp) != null;
		} else {
			final String url = link.getUrl(UrlInfo.Type.Catalog) + "/" + actionId;
			return testService(
				activity,
				TOPUP_ACTION,
				url
			);
		}
	}

	static void runTopupDialog(Activity activity, INetworkLink link, String actionId) {
		try {
			final String url = link.getUrl(UrlInfo.Type.Catalog) + "/" + actionId;
			final Intent intent = new Intent(TOPUP_ACTION, Uri.parse(url));
			final NetworkAuthenticationManager mgr = link.authenticationManager();
			if (mgr != null) {
				for (Map.Entry<String,String> entry : mgr.getTopupData().entrySet()) {
					intent.putExtra(entry.getKey(), entry.getValue());
				}
			}
			if (PackageUtil.canBeStarted(activity, intent, true)) {
				activity.startActivity(intent);
			}
		} catch (ActivityNotFoundException e) {
		}
	}

	static void openInBrowser(Context context, String url) {
		if (url != null) {
			url = NetworkLibrary.Instance().rewriteUrl(url, true);
			context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
		}
	}

	static void openTree(Context context, NetworkTree tree) {
		final Class<?> clz = tree instanceof NetworkBookTree
			? NetworkBookInfoActivity.class : NetworkBaseActivity.class;
		context.startActivity(
			new Intent(context.getApplicationContext(), clz)
				.putExtra(NetworkBaseActivity.TREE_KEY_KEY, tree.getUniqueKey())
		);
	}

	public static NetworkTree getTreeFromIntent(Intent intent) {
		final NetworkLibrary library = NetworkLibrary.Instance();
		final NetworkTree.Key key = (NetworkTree.Key)intent.getSerializableExtra(NetworkBaseActivity.TREE_KEY_KEY);
		return library.getTreeByKey(key);
	}
}
