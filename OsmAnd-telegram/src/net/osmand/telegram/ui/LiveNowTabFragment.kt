package net.osmand.telegram.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import net.osmand.telegram.R
import net.osmand.telegram.TelegramApplication
import net.osmand.telegram.helpers.TelegramHelper.TelegramAuthorizationState
import net.osmand.telegram.helpers.TelegramHelper.TelegramListener
import org.drinkless.td.libcore.telegram.TdApi

class LiveNowTabFragment : Fragment(), TelegramListener {

	companion object {
		private const val CHAT_VIEW_TYPE = 0
		private const val CONTACT_VIEW_TYPE = 1
	}

	private val app: TelegramApplication
		get() = activity?.application as TelegramApplication

	private val telegramHelper get() = app.telegramHelper
	private val osmandHelper get() = app.osmandHelper
	private val settings get() = app.settings

	private val adapter = LiveNowListAdapter()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val mainView = inflater.inflate(R.layout.fragment_live_now_tab, container, false)
		mainView.findViewById<RecyclerView>(R.id.recycler_view).apply {
			layoutManager = LinearLayoutManager(context)
			adapter = this@LiveNowTabFragment.adapter
		}
		return mainView
	}

	override fun onResume() {
		super.onResume()
		updateList()
	}

	override fun onTelegramStatusChanged(prevTelegramAuthorizationState: TelegramAuthorizationState,
										 newTelegramAuthorizationState: TelegramAuthorizationState) {
		when (newTelegramAuthorizationState) {
			TelegramAuthorizationState.READY -> {
				updateList()
			}
			TelegramAuthorizationState.CLOSED,
			TelegramAuthorizationState.UNKNOWN -> {
				adapter.items = emptyList()
			}
			else -> Unit
		}
	}

	override fun onTelegramChatsRead() {
		updateList()
	}

	override fun onTelegramChatsChanged() {
		updateList()
	}

	override fun onTelegramChatChanged(chat: TdApi.Chat) {
		updateList()
	}

	override fun onTelegramUserChanged(user: TdApi.User) {
		updateList()
	}

	override fun onTelegramError(code: Int, message: String) {
	}

	override fun onSendLiveLocationError(code: Int, message: String) {
	}

	private fun updateList() {
		val res = mutableListOf<Any>()
		for ((id, messages) in telegramHelper.getMessagesByChatIds()) {
			telegramHelper.getChat(id)?.let { chat ->
				res.add(chat)
				if (chat.type !is TdApi.ChatTypePrivate && chat.type !is TdApi.ChatTypeSecret && messages.size > 1) {
					messages.forEach { message ->
						telegramHelper.getUser(message.senderUserId)?.let { user ->
							res.add(user)
						}
					}
				}
			}
		}
		adapter.items = res
	}

	inner class LiveNowListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		var items: List<Any> = emptyList()
			set(value) {
				field = value
				notifyDataSetChanged()
			}

		override fun getItemViewType(position: Int): Int {
			return when (items[position]) {
				is TdApi.Chat -> CHAT_VIEW_TYPE
				else -> CONTACT_VIEW_TYPE
			}
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			val inflater = LayoutInflater.from(parent.context)
			return when (viewType) {
				CHAT_VIEW_TYPE -> ChatViewHolder(inflater.inflate(R.layout.live_now_chat_card, parent, false))
				else -> ContactViewHolder(inflater.inflate(R.layout.live_now_contact_item, parent, false))
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val lastItem = position == itemCount - 1
			val item = items[position]
			if (item is TdApi.Chat && holder is ChatViewHolder) {
				val nextItemIsContact = !lastItem && items[position + 1] is TdApi.User
				val chatTitle = item.title

				var drawable: Drawable? = null
				var bitmap: Bitmap? = null
				val chatPhoto = item.photo?.small
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
				holder.title?.text = chatTitle
				holder.description?.text = "Chat description" // FIXME
				holder.imageButton?.setImageDrawable(app.uiUtils.getThemedIcon(R.drawable.ic_overflow_menu_white))
				holder.imageButton?.setOnClickListener {
					Toast.makeText(context, "Options", Toast.LENGTH_SHORT).show() // FIXME
				}
				holder.showOnMapRow?.setOnClickListener {
					holder.showOnMapSwitch?.isChecked = !holder.showOnMapSwitch?.isChecked!!
				}
				holder.showOnMapSwitch?.setOnCheckedChangeListener(null)
				holder.showOnMapSwitch?.isChecked = settings.isShowingChatOnMap(chatTitle)
				holder.showOnMapSwitch?.setOnCheckedChangeListener { _, isChecked ->
					settings.showChatOnMap(chatTitle, isChecked)
					if (settings.hasAnyChatToShowOnMap()) {
						if (osmandHelper.isOsmandNotInstalled()) {
							if (isChecked) {
								activity?.let {
									MainActivity.OsmandMissingDialogFragment().show(it.supportFragmentManager, null)
								}
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
				holder.bottomDivider?.visibility = if (nextItemIsContact) View.VISIBLE else View.GONE
				holder.bottomShadow?.visibility = if (lastItem) View.VISIBLE else View.GONE
			} else if (item is TdApi.User && holder is ContactViewHolder) {
				// telegramHelper.getUserPhotoPath(user)
				holder.icon?.setImageDrawable(app.uiUtils.getThemedIcon(R.drawable.ic_group)) // FIXME
				holder.title?.text = "${item.firstName} ${item.lastName}"
				holder.description?.text = "User description" // FIXME
				holder.bottomShadow?.visibility = if (lastItem) View.VISIBLE else View.GONE
			}
		}

		override fun getItemCount() = items.size

		inner class ContactViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
			val icon: ImageView? = view.findViewById(R.id.icon)
			val title: TextView? = view.findViewById(R.id.title)
			val description: TextView? = view.findViewById(R.id.description)
			val bottomShadow: View? = view.findViewById(R.id.bottom_shadow)
		}

		inner class ChatViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
			val icon: ImageView? = view.findViewById(R.id.icon)
			val title: TextView? = view.findViewById(R.id.title)
			val description: TextView? = view.findViewById(R.id.description)
			val imageButton: ImageView? = view.findViewById(R.id.image_button)
			val showOnMapRow: View? = view.findViewById(R.id.show_on_map_row)
			val showOnMapSwitch: Switch? = view.findViewById(R.id.show_on_map_switch)
			val bottomDivider: View? = view.findViewById(R.id.bottom_divider)
			val bottomShadow: View? = view.findViewById(R.id.bottom_shadow)
		}
	}
}
