package net.osmand.plus;

import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListAdapter;
import android.widget.TextView;

public class ContextMenuAdapter {
	
	public interface OnContextMenuClick {
		//boolean return type needed to desribe if drawer needed to be close or not
		public boolean onContextMenuClick(int itemId, int pos, boolean isChecked);
	}
	
	private final Context ctx;
	private View anchor;
	private int defaultLayoutId = Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB ?
			R.layout.list_menu_item : R.layout.list_menu_item_native;
	final TIntArrayList items = new TIntArrayList();
	final TIntArrayList isCategory = new TIntArrayList();
	final ArrayList<String> itemNames = new ArrayList<String>();
	final ArrayList<OnContextMenuClick> listeners = new ArrayList<ContextMenuAdapter.OnContextMenuClick>();
	final TIntArrayList selectedList = new TIntArrayList();
	final TIntArrayList layoutIds = new TIntArrayList();
	final TIntArrayList iconList = new TIntArrayList();
	final TIntArrayList iconListLight = new TIntArrayList();

	public ContextMenuAdapter(Context ctx) {
		this.ctx = ctx;
	}
	
	public void setAnchor(View anchor) {
		this.anchor = anchor;
	}
	
	public View getAnchor() {
		return anchor;
	}
	
	public int length(){
		return items.size();
	}
	
	public int getItemId(int pos){
		return items.get(pos);
	}
	
	public OnContextMenuClick getClickAdapter(int i) {
		return listeners.get(i);
	}
	
	public String getItemName(int pos){
		return itemNames.get(pos);
	}
	
	public void setItemName(int pos, String str) {
		itemNames.set(pos, str);
	}
	
	public int getSelection(int pos) {
		return selectedList.get(pos);
	}
	
	public void setSelection(int pos, int s) {
		selectedList.set(pos, s);
	}
	
	public int getImageId(int pos, boolean light) {
		if(!light || iconListLight.get(pos) == 0) {
			return iconList.get(pos);
		}
		return iconListLight.get(pos);
	}
	
	public int getBackgroundColor(Context ctx, boolean holoLight) {
		if (holoLight) {
			return ctx.getResources().getColor(R.color.color_white);
		} else {
			return ctx.getResources().getColor(R.color.dark_drawer_bg_color);
		}
	}
	
	
	public boolean isCategory(int pos) {
		return isCategory.get(pos) > 0;
	}
	
	public Item item(String name){
		Item i = new Item();
		i.id = (name.hashCode() << 4) | items.size();
		i.name = name;
		return i;
	}
	
	public Item item(int resId){
		Item i = new Item();
		i.id = resId;
		i.name = ctx.getString(resId);
		return i;
	}
	
	public class Item {
		int icon = 0;
		int lightIcon = 0;
		int id;
		String name;
		int selected = -1;
		int layout = -1;
		boolean cat;
		int pos = -1;
		private OnContextMenuClick listener;

		private Item() {
		}

		public Item icon(int icon) {
			this.icon = icon;
			return this;
		}

		public Item icons(int icon, int lightIcon) {
			this.icon = icon;
			this.lightIcon = lightIcon;
			return this;
		}

		public Item position(int pos) {
			this.pos = pos;
			return this;
		}

		public Item selected(int selected) {
			this.selected = selected;
			return this;
		}
		
		public Item layout(int l) {
			this.layout = l;
			return this;
		}

		public Item listen(OnContextMenuClick l) {
			this.listener = l;
			return this;

		}

		public void reg() {
			if (pos >= items.size() || pos < 0) {
				pos = items.size();
			}
			items.insert(pos, id);
			itemNames.add(pos, name);
			selectedList.insert(pos, selected);
			layoutIds.insert(pos, layout);
			iconList.insert(pos, icon);
			iconListLight.insert(pos, lightIcon);
			listeners.add(pos, listener);
			isCategory.insert(pos, cat ? 1 : 0);
		}

		public Item setCategory(boolean b) {
			cat = b;
			return this;
		}

	}
	
	public String[] getItemNames() {
		return itemNames.toArray(new String[itemNames.size()]);
	}
	
	public void removeItem(int pos) {
		items.removeAt(pos);
		itemNames.remove(pos);
		selectedList.removeAt(pos);
		iconList.removeAt(pos);
		iconListLight.removeAt(pos);
		listeners.remove(pos);
		isCategory.removeAt(pos);
		layoutIds.removeAt(pos);
	}

	public int getLayoutId(int position) {
		int l = layoutIds.get(position);
		if(l != -1) {
			return l;
		}
		return defaultLayoutId; 
	}
	

	public ListAdapter createListAdapter(final Activity activity, final boolean holoLight) {
		final int padding = (int) (12 * activity.getResources().getDisplayMetrics().density + 0.5f);
		final int layoutId = defaultLayoutId;
		ListAdapter listadapter = new ArrayAdapter<String>(activity, layoutId, R.id.title,
				getItemNames()) {
			@Override
			public View getView(final int position, View convertView, ViewGroup parent) {
				// User super class to create the View
				View v = convertView;
				Integer lid = getLayoutId(position);
				if (v == null || (v.getTag() != lid)) {
					v = activity.getLayoutInflater().inflate(lid, null);
					v.setTag(lid);
				}
				TextView tv = (TextView) v.findViewById(R.id.title);
				tv.setText(getItemName(position));

				// Put the image on the TextView
				if (getImageId(position, holoLight) != 0) {
					tv.setCompoundDrawablesWithIntrinsicBounds(getImageId(position, holoLight), 0, 0, 0);
				} else {
					tv.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_transparent, 0, 0, 0);
				}
				tv.setCompoundDrawablePadding(padding);
				
				if(isCategory(position)) {
					tv.setTypeface(Typeface.DEFAULT_BOLD);
				} else {
					tv.setTypeface(null);
				}

				final CheckBox ch = ((CheckBox) v.findViewById(R.id.check_item));
				if(selectedList.get(position) != -1) {
					ch.setOnCheckedChangeListener(null);
					ch.setVisibility(View.VISIBLE);
					ch.setSelected(selectedList.get(position) > 0);
					ch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
						
						@Override
						public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
							OnContextMenuClick ca = getClickAdapter(position);
							if(ca != null) {
								ca.onContextMenuClick(position, position, isChecked);
							}
						}
					});
					ch.setVisibility(View.VISIBLE);
				} else {
					ch.setVisibility(View.GONE);
				}
				return v;
			}
		};
		return listadapter;
	}

	

}
