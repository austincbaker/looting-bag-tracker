package com.lootingbagtracker;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.PluginLootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Looting Bag Loot Tracker",
	description = "Tracks loot that goes into an open looting bag during chest and container interactions",
	tags = {"loot", "tracker", "looting bag", "thieving", "chest"}
)
public class LootingBagTrackerPlugin extends Plugin
{
	// net.runelite.api.gameval.InventoryID
	private static final int INV_CONTAINER = 93;
	private static final int LOOTING_BAG_CONTAINER = 516;

	// net.runelite.api.gameval.ItemID
	private static final int ITEM_LOOTING_BAG_OPEN = 22586;

	private static final int SNAPSHOT_TIMEOUT_TICKS = 24;

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	private Multiset<Integer> bagSnapshot;
	private String pendingSourceName;
	private int snapshotTicksRemaining;

	@Override
	protected void shutDown()
	{
		reset();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!playerHasOpenBag())
		{
			return;
		}

		final MenuAction action = event.getMenuAction();
		final String option = event.getMenuOption();
		final String target = event.getMenuTarget();
		log.info("[LootingBagTracker] Menu clicked: action={} (id={}), option='{}', target='{}'",
			action, action.getId(), option, target);

		if (!isObjectOp(action))
		{
			log.info("[LootingBagTracker] Action {} is not an object op - skipping", action);
			return;
		}

		if (!option.equals("Open") && !option.equals("Unlock")
			&& !option.equals("Search") && !option.equals("Search for traps")
			&& !option.equals("Steal") && !option.equals("Loot"))
		{
			log.info("[LootingBagTracker] Option '{}' not in tracked list - skipping", option);
			return;
		}

		final String sourceName = Text.removeTags(target);
		final Multiset<Integer> snapshot = getBagContents();
		log.info("[LootingBagTracker] Snapshotting looting bag for: '{}' - bag has {} item stacks", sourceName, snapshot.elementSet().size());
		bagSnapshot = snapshot;
		pendingSourceName = sourceName;
		snapshotTicksRemaining = SNAPSHOT_TIMEOUT_TICKS;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (bagSnapshot == null || event.getContainerId() != LOOTING_BAG_CONTAINER)
		{
			return;
		}

		log.info("[LootingBagTracker] Looting bag changed while snapshot active");

		final Multiset<Integer> currentBag = getBagContents();
		final List<ItemStack> gained = Multisets.difference(currentBag, bagSnapshot).entrySet().stream()
			.filter(e -> e.getElement() > 0)
			.map(e -> new ItemStack(e.getElement(), e.getCount()))
			.collect(Collectors.toList());

		log.info("[LootingBagTracker] Bag changed while snapshot active - snapshot={} stacks, current={} stacks, gained={} stacks",
			bagSnapshot.elementSet().size(), currentBag.elementSet().size(), gained.size());

		if (gained.isEmpty())
		{
			log.info("[LootingBagTracker] No new items gained in bag - ignoring change");
			return;
		}

		for (ItemStack item : gained)
		{
			log.info("[LootingBagTracker] Gained item: id={}, qty={}", item.getId(), item.getQuantity());
		}

		log.info("[LootingBagTracker] Posting PluginLootReceived for '{}' with {} item stacks", pendingSourceName, gained.size());
		eventBus.post(PluginLootReceived.builder()
			.source(this)
			.name(pendingSourceName)
			.type(LootRecordType.EVENT)
			.items(gained)
			.build());
		reset();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (snapshotTicksRemaining > 0)
		{
			snapshotTicksRemaining--;
			log.info("[LootingBagTracker] Snapshot timeout tick: {} ticks remaining", snapshotTicksRemaining);
			if (snapshotTicksRemaining == 0)
			{
				log.info("[LootingBagTracker] Snapshot timed out - resetting");
				reset();
			}
		}
	}

	private void reset()
	{
		bagSnapshot = null;
		pendingSourceName = null;
		snapshotTicksRemaining = 0;
	}

	private Multiset<Integer> getBagContents()
	{
		final Multiset<Integer> contents = HashMultiset.create();
		final ItemContainer bag = client.getItemContainer(LOOTING_BAG_CONTAINER);
		if (bag != null)
		{
			for (Item item : bag.getItems())
			{
				if (item.getId() > 0)
				{
					contents.add(item.getId(), item.getQuantity());
				}
			}
		}
		return contents;
	}

	private boolean playerHasOpenBag()
	{
		final ItemContainer inv = client.getItemContainer(INV_CONTAINER);
		return inv != null && inv.contains(ITEM_LOOTING_BAG_OPEN);
	}

	private static boolean isObjectOp(MenuAction action)
	{
		final int id = action.getId();
		return (id >= MenuAction.GAME_OBJECT_FIRST_OPTION.getId()
			&& id <= MenuAction.GAME_OBJECT_FOURTH_OPTION.getId())
			|| id == MenuAction.GAME_OBJECT_FIFTH_OPTION.getId();
	}
}
