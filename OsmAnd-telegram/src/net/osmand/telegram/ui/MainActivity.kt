package net.osmand.telegram.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.support.design.widget.BottomNavigationView
import android.support.v4.app.*
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.*
import android.view.*
import android.widget.Toast
import net.osmand.PlatformUtil
import net.osmand.telegram.R
import net.osmand.telegram.TelegramApplication
import net.osmand.telegram.helpers.TelegramHelper
import net.osmand.telegram.helpers.TelegramHelper.*
import net.osmand.telegram.ui.LoginDialogFragment.LoginDialogType
import net.osmand.telegram.ui.views.LockableViewPager
import net.osmand.telegram.utils.AndroidUtils
import org.drinkless.td.libcore.telegram.TdApi
import java.lang.ref.WeakReference


class MainActivity : AppCompatActivity(), TelegramListener {

	companion object {
		private const val PERMISSION_REQUEST_LOCATION = 1

		private const val LOGIN_MENU_ID = 0
		private const val LOGOUT_MENU_ID = 1
		private const val PROGRESS_MENU_ID = 2

		private const val MY_LOCATION_TAB_POS = 0
		private const val LIVE_NOW_TAB_POS = 1
	}

	private val log = PlatformUtil.getLog(TelegramHelper::class.java)

	private var telegramAuthorizationRequestHandler: TelegramAuthorizationRequestHandler? = null
	private var paused: Boolean = false

	private lateinit var chatsView: RecyclerView
	private lateinit var chatViewAdapter: ChatsAdapter
	private lateinit var chatViewManager: RecyclerView.LayoutManager

	private val app: TelegramApplication
		get() = application as TelegramApplication

	private val telegramHelper get() = app.telegramHelper
	private val osmandHelper get() = app.osmandHelper
	private val settings get() = app.settings

	private val listeners: MutableList<WeakReference<TelegramListener>> = mutableListOf()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		paused = false

		chatViewManager = LinearLayoutManager(this)
		chatViewAdapter = ChatsAdapter()

		chatsView = findViewById<RecyclerView>(R.id.groups_view).apply {
			//setHasFixedSize(true)

			// use a linear layout manager
			layoutManager = chatViewManager

			// specify an viewAdapter (see also next example)
			adapter = chatViewAdapter
		}

		val viewPager = findViewById<LockableViewPager>(R.id.view_pager).apply {
			swipeLocked = true
			offscreenPageLimit = 2
			adapter = ViewPagerAdapter(supportFragmentManager)
		}

		findViewById<BottomNavigationView>(R.id.bottom_navigation).setOnNavigationItemSelectedListener {
			var pos = -1
			when (it.itemId) {
				R.id.action_my_location -> pos = MY_LOCATION_TAB_POS
				R.id.action_live_now -> pos = LIVE_NOW_TAB_POS
			}
			if (pos != -1 && pos != viewPager.currentItem) {
				// FIXME
				chatsView.visibility = if (pos == MY_LOCATION_TAB_POS) View.VISIBLE else View.GONE
				viewPager.visibility = if (pos == LIVE_NOW_TAB_POS) View.VISIBLE else View.GONE
				viewPager.currentItem = pos
				return@setOnNavigationItemSelectedListener true
			}
			false
		}

		if (!LoginDialogFragment.welcomeDialogShown) {
			LoginDialogFragment.showWelcomeDialog(supportFragmentManager)
		}

		telegramAuthorizationRequestHandler = telegramHelper.setTelegramAuthorizationRequestHandler(object : TelegramAuthorizationRequestListener {
			override fun onRequestTelegramAuthenticationParameter(parameterType: TelegramAuthenticationParameterType) {
				runOnUi {
					showLoginDialog(parameterType)
				}
			}

			override fun onTelegramAuthorizationRequestError(code: Int, message: String) {
				runOnUi {
					Toast.makeText(this@MainActivity, "$code - $message", Toast.LENGTH_LONG).show()
				}
			}
		})
		telegramHelper.listener = this
		if (!telegramHelper.isInit()) {
			telegramHelper.init()
		}

		if (osmandHelper.isOsmandBound() && !osmandHelper.isOsmandConnected()) {
			osmandHelper.connectOsmand()
		}
	}

	override fun onAttachFragment(fragment: Fragment?) {
		if (fragment is TelegramListener) {
			listeners.add(WeakReference(fragment))
		}
	}

	override fun onResume() {
		super.onResume()
		paused = false

		invalidateOptionsMenu()
		updateTitle()
		updateChatsList()

		if (settings.hasAnyChatToShareLocation() && !AndroidUtils.isLocationPermissionAvailable(this)) {
			requestLocationPermission()
		} else if (settings.hasAnyChatToShowOnMap() && osmandHelper.isOsmandNotInstalled()) {
			showOsmandMissingDialog()
		}
	}

	override fun onPause() {
		super.onPause()
		telegramHelper.listener = null

		paused = true
	}

	override fun onStop() {
		super.onStop()
		settings.save()
	}

	override fun onDestroy() {
		super.onDestroy()

		if (app.telegramService == null) {
			app.cleanupResources()
		}
	}

	override fun onTelegramStatusChanged(prevTelegramAuthorizationState: TelegramAuthorizationState,
										 newTelegramAuthorizationState: TelegramAuthorizationState) {
		runOnUi {
			val fm = supportFragmentManager
			when (newTelegramAuthorizationState) {
				TelegramAuthorizationState.READY -> LoginDialogFragment.dismiss(fm)
				else -> Unit
			}
			invalidateOptionsMenu()
			updateTitle()

			when (newTelegramAuthorizationState) {
				TelegramAuthorizationState.READY -> {
					updateChatsList()
				}
				TelegramAuthorizationState.CLOSED,
				TelegramAuthorizationState.UNKNOWN -> {
					chatViewAdapter.chats = emptyList()
				}
				else -> Unit
			}
			listeners.forEach {
				it.get()?.onTelegramStatusChanged(prevTelegramAuthorizationState, newTelegramAuthorizationState)
			}
		}
	}

	override fun onTelegramChatsRead() {
		runOnUi {
			removeNonexistingChatsFromSettings()
			updateChatsList()
			listeners.forEach { it.get()?.onTelegramChatsRead() }
		}
	}

	override fun onTelegramChatsChanged() {
		runOnUi {
			updateChatsList()
			listeners.forEach { it.get()?.onTelegramChatsChanged() }
		}
	}

	override fun onTelegramChatChanged(chat: TdApi.Chat) {
		runOnUi {
			updateChatsList()
			listeners.forEach { it.get()?.onTelegramChatChanged(chat) }
		}
	}

	override fun onTelegramUserChanged(user: TdApi.User) {
		val message = telegramHelper.getUserMessage(user)
		if (message != null) {
			app.showLocationHelper.showLocationOnMap(message)
		}
		runOnUi {
			listeners.forEach { it.get()?.onTelegramUserChanged(user) }
		}
	}

	override fun onTelegramError(code: Int, message: String) {
		runOnUi {
			Toast.makeText(this@MainActivity, "$code - $message", Toast.LENGTH_LONG).show()
			listeners.forEach { it.get()?.onTelegramError(code, message) }
		}
	}

	override fun onSendLiveLocationError(code: Int, message: String) {
		log.error("Send live location error: $code - $message")
		app.isInternetConnectionAvailable(true)
		runOnUi {
			listeners.forEach { it.get()?.onSendLiveLocationError(code, message) }
		}
	}

	private fun removeNonexistingChatsFromSettings() {
		val presentChatTitles = telegramHelper.getChatTitles()
		settings.removeNonexistingChats(presentChatTitles)
	}

	private fun updateChatsList() {
		val chatList = telegramHelper.getChatList()
		val chats: MutableList<TdApi.Chat> = mutableListOf()
		for (orderedChat in chatList) {
			val chat = telegramHelper.getChat(orderedChat.chatId)
			if (chat != null) {
				chats.add(chat)
			}
		}
		chatViewAdapter.chats = chats
	}

	fun loginTelegram() {
		if (telegramHelper.getTelegramAuthorizationState() != TelegramAuthorizationState.CLOSED) {
			telegramHelper.logout()
		}
		telegramHelper.init()
	}

	fun logoutTelegram(silent: Boolean = false) {
		if (telegramHelper.getTelegramAuthorizationState() == TelegramAuthorizationState.READY) {
			telegramHelper.logout()
		} else {
			invalidateOptionsMenu()
			updateTitle()
			if (!silent) {
				Toast.makeText(this, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
			}
		}
	}

	fun closeTelegram() {
		telegramHelper.close()
	}

	private fun runOnUi(action: (() -> Unit)) {
		if (!paused) {
			runOnUiThread(action)
		}
	}

	override fun onOptionsItemSelected(item: MenuItem?): Boolean {
		return when (item?.itemId) {
			LOGIN_MENU_ID -> {
				loginTelegram()
				true
			}
			LOGOUT_MENU_ID -> {
				logoutTelegram()
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		if (menu != null) {
			menu.clear()
			when (telegramHelper.getTelegramAuthorizationState()) {
				TelegramAuthorizationState.UNKNOWN,
				TelegramAuthorizationState.WAIT_PARAMETERS,
				TelegramAuthorizationState.WAIT_PHONE_NUMBER,
				TelegramAuthorizationState.WAIT_CODE,
				TelegramAuthorizationState.WAIT_PASSWORD,
				TelegramAuthorizationState.LOGGING_OUT,
				TelegramAuthorizationState.CLOSING -> createProgressMenuItem(menu)
				TelegramAuthorizationState.READY -> createMenuItem(menu, LOGOUT_MENU_ID, R.string.shared_string_logout,
						MenuItem.SHOW_AS_ACTION_WITH_TEXT or MenuItem.SHOW_AS_ACTION_ALWAYS)
				TelegramAuthorizationState.CLOSED -> createMenuItem(menu, LOGIN_MENU_ID, R.string.shared_string_login,
						MenuItem.SHOW_AS_ACTION_WITH_TEXT or MenuItem.SHOW_AS_ACTION_ALWAYS)
			}
		}
		return super.onCreateOptionsMenu(menu)
	}

	private fun createMenuItem(m: Menu, id: Int, titleRes: Int, menuItemType: Int): MenuItem {
		val menuItem = m.add(0, id, 0, titleRes)
		menuItem.setOnMenuItemClickListener { item -> onOptionsItemSelected(item) }
		menuItem.setShowAsAction(menuItemType)
		return menuItem
	}

	private fun createProgressMenuItem(m: Menu): MenuItem {

		val menuItem = m.add(0, PROGRESS_MENU_ID, 0, "")
		menuItem.actionView = layoutInflater.inflate(R.layout.action_progress_bar, null)
		menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
		return menuItem
	}

	private fun updateTitle() {
		title = when (telegramHelper.getTelegramAuthorizationState()) {

			TelegramAuthorizationState.UNKNOWN,
			TelegramAuthorizationState.WAIT_PHONE_NUMBER,
			TelegramAuthorizationState.WAIT_CODE,
			TelegramAuthorizationState.WAIT_PASSWORD,
			TelegramAuthorizationState.READY,
			TelegramAuthorizationState.CLOSED -> getString(R.string.app_name)

			TelegramAuthorizationState.WAIT_PARAMETERS -> getString(R.string.initialization) + "..."
			TelegramAuthorizationState.LOGGING_OUT -> getString(R.string.logging_out) + "..."
			TelegramAuthorizationState.CLOSING -> getString(R.string.closing) + "..."
		}
	}

	private fun showLoginDialog(telegramAuthenticationParameterType: TelegramAuthenticationParameterType) {
		when (telegramAuthenticationParameterType) {
			TelegramAuthenticationParameterType.PHONE_NUMBER -> LoginDialogFragment.showDialog(supportFragmentManager, LoginDialogType.ENTER_PHONE_NUMBER)
			TelegramAuthenticationParameterType.CODE -> LoginDialogFragment.showDialog(supportFragmentManager, LoginDialogType.ENTER_CODE)
			TelegramAuthenticationParameterType.PASSWORD -> LoginDialogFragment.showDialog(supportFragmentManager, LoginDialogType.ENTER_PASSWORD)
		}
	}

	fun applyAuthParam(loginDialogFragment: LoginDialogFragment?, loginDialogType: LoginDialogType, text: String) {
		loginDialogFragment?.showProgress()
		when (loginDialogType) {
			LoginDialogType.ENTER_PHONE_NUMBER -> telegramAuthorizationRequestHandler?.applyAuthenticationParameter(TelegramAuthenticationParameterType.PHONE_NUMBER, text)
			LoginDialogType.ENTER_CODE -> telegramAuthorizationRequestHandler?.applyAuthenticationParameter(TelegramAuthenticationParameterType.CODE, text)
			LoginDialogType.ENTER_PASSWORD -> telegramAuthorizationRequestHandler?.applyAuthenticationParameter(TelegramAuthenticationParameterType.PASSWORD, text)
		}
	}

	private fun requestLocationPermission() {
		ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_LOCATION)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		when (requestCode) {
			PERMISSION_REQUEST_LOCATION -> {
				if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					if (settings.hasAnyChatToShareLocation()) {
						app.shareLocationHelper.startSharingLocation()
					}
				} else {
					settings.stopSharingLocationToChats()
					app.shareLocationHelper.stopSharingLocation()
					updateChatsList()
				}
				if (settings.hasAnyChatToShowOnMap() && osmandHelper.isOsmandNotInstalled()) {
					showOsmandMissingDialog()
				}
			}
		}
	}

	fun showOsmandMissingDialog() {
		OsmandMissingDialogFragment().show(supportFragmentManager, null)
	}

	class OsmandMissingDialogFragment : DialogFragment() {

		override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
			val builder = AlertDialog.Builder(requireContext())
			builder.setView(R.layout.install_osmand_dialog)
					.setNegativeButton("Cancel", null)
					.setPositiveButton("Install") { _, _ ->
						val intent = Intent()
						intent.data = Uri.parse("market://details?id=net.osmand.plus")
						startActivity(intent)
					}
			return builder.create()
		}
	}

	class ViewPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {

		private val fragments = listOf(MyLocationTabFragment(), LiveNowTabFragment())

		override fun getItem(position: Int) = fragments[position]

		override fun getCount() = fragments.size
	}

	inner class ChatsAdapter :
			RecyclerView.Adapter<ChatsAdapter.ViewHolder>() {

		var chats: List<TdApi.Chat> = emptyList()
			set(value) {
				field = value
				notifyDataSetChanged()
			}

		inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
			val icon: AppCompatImageView? = view.findViewById(R.id.icon)
			val groupName: AppCompatTextView? = view.findViewById(R.id.name)
			val shareLocationSwitch: SwitchCompat? = view.findViewById(R.id.share_location_switch)
			val showOnMapSwitch: SwitchCompat? = view.findViewById(R.id.show_on_map_switch)
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val view = LayoutInflater.from(parent.context).inflate(R.layout.chat_list_item, parent, false)
			return ViewHolder(view)
		}

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val chat = chats[position]
			val chatTitle = chat.title
			holder.groupName?.text = chatTitle

			var drawable: Drawable? = null
			var bitmap: Bitmap? = null
			val chatPhoto = chat.photo?.small
			if (chatPhoto != null && chatPhoto.local.path.isNotEmpty()) {
				bitmap = app.uiUtils.getCircleBitmap(chatPhoto.local.path)
			}
			if (bitmap == null) {
				drawable = app.uiUtils.getThemedIcon(R.drawable.ic_group)
			}
			if (bitmap != null) {
				holder.icon?.setImageBitmap(bitmap)
			} else {
				holder.icon?.setImageDrawable(drawable)
			}
			holder.shareLocationSwitch?.setOnCheckedChangeListener(null)
			holder.shareLocationSwitch?.isChecked = settings.isSharingLocationToChat(chatTitle)
			holder.shareLocationSwitch?.setOnCheckedChangeListener { view, isChecked ->
				settings.shareLocationToChat(chatTitle, isChecked)
				if (settings.hasAnyChatToShareLocation()) {
					if (!AndroidUtils.isLocationPermissionAvailable(view.context)) {
						if (isChecked) {
							requestLocationPermission()
						}
					} else {
						app.shareLocationHelper.startSharingLocation()
					}
				} else {
					app.shareLocationHelper.stopSharingLocation()
				}
			}

			holder.showOnMapSwitch?.setOnCheckedChangeListener(null)
			holder.showOnMapSwitch?.isChecked = settings.isShowingChatOnMap(chatTitle)
			holder.showOnMapSwitch?.setOnCheckedChangeListener { _, isChecked ->
				settings.showChatOnMap(chatTitle, isChecked)
				if (settings.hasAnyChatToShowOnMap()) {
					if (osmandHelper.isOsmandNotInstalled()) {
						if (isChecked) {
							showOsmandMissingDialog()
						}
					} else {
						if (isChecked) {
							app.showLocationHelper.showChatMessages(chatTitle)
						} else {
							app.showLocationHelper.hideChatMessages(chatTitle)
						}
						app.showLocationHelper.startShowingLocation()
					}
				} else {
					app.showLocationHelper.stopShowingLocation()
					if (!isChecked) {
						app.showLocationHelper.hideChatMessages(chatTitle)
					}
				}
			}
		}

		override fun getItemCount() = chats.size
	}
}
