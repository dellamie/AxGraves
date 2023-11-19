package com.artillexstudios.axgraves.grave;

import com.artillexstudios.axapi.entity.PacketEntityFactory;
import com.artillexstudios.axapi.entity.impl.PacketArmorStand;
import com.artillexstudios.axapi.hologram.Hologram;
import com.artillexstudios.axapi.hologram.HologramFactory;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.serializers.Serializers;
import com.artillexstudios.axapi.utils.EquipmentSlot;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axgraves.api.events.GraveInteractEvent;
import com.artillexstudios.axgraves.api.events.GraveOpenEvent;
import com.artillexstudios.axgraves.utils.LocationUtils;
import com.artillexstudios.axgraves.utils.Utils;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.StorageGui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static com.artillexstudios.axgraves.AxGraves.CONFIG;
import static com.artillexstudios.axgraves.AxGraves.EXECUTOR;
import static com.artillexstudios.axgraves.AxGraves.MESSAGES;

public class Grave {
    private final long spawned;
    private final Location location;
    private final OfflinePlayer player;
    private final String playerName;
    private final StorageGui gui;
    private int storedXP;
    private final PacketArmorStand entity;
    private Hologram hologram;

    public Grave(Location loc, Player player, Inventory inventory, int storedXP) {
        this.location = LocationUtils.getCenterOf(loc);
        this.player = player;
        this.playerName = player.getName();
        this.gui = Gui.storage()
                .title(StringUtils.format(MESSAGES.getString("gui-name").replace("%player%", playerName)))
                .rows(4)
                .create();
        this.storedXP = storedXP;
        this.spawned = System.currentTimeMillis();

        for (ItemStack it : inventory.getContents()) {
            if (it == null) continue;
            gui.addItem(it);
        }

        entity = (PacketArmorStand) PacketEntityFactory.get().spawnEntity(this.location.clone().add(0, -1.2, 0), EntityType.ARMOR_STAND);
        entity.setItem(EquipmentSlot.HELMET, Utils.getPlayerHead(player));
        entity.setSmall(true);
        entity.setInvisible(true);
        entity.setHasBasePlate(false);

        entity.onClick(event -> Scheduler.get().run(scheduledTask -> {

            final GraveInteractEvent deathChestInteractEvent = new GraveInteractEvent(player, this);
            Bukkit.getPluginManager().callEvent(deathChestInteractEvent);
            if (deathChestInteractEvent.isCancelled()) return;

            if (this.storedXP != 0) {
                event.getPlayer().giveExp(this.storedXP);
                this.storedXP = 0;
            }

            if (event.getHand().equals(org.bukkit.inventory.EquipmentSlot.HAND) && event.getPlayer().isSneaking()) {
                if (!CONFIG.getBoolean("enable-instant-pickup", true)) return;
                if (CONFIG.getBoolean("instant-pickup-only-own", false) && !player.getUniqueId().equals(event.getPlayer().getUniqueId())) return;

                for (ItemStack it : gui.getInventory().getContents()) {
                    if (it == null) continue;

                    final Collection<ItemStack> ar = event.getPlayer().getInventory().addItem(it).values();
                    if (ar.isEmpty()) {
                        it.setAmount(0);
                        continue;
                    }

                    it.setAmount(ar.iterator().next().getAmount());
                }

                update();
                return;
            }

            final GraveOpenEvent deathChestOpenEvent = new GraveOpenEvent(player, this);
            Bukkit.getPluginManager().callEvent(deathChestOpenEvent);
            if (deathChestOpenEvent.isCancelled()) return;

            gui.open(event.getPlayer());
        }));

        EXECUTOR.execute(() -> {
            hologram = HologramFactory.get().spawnHologram(location.add(0, CONFIG.getFloat("hologram-height", 1.2f), 0), Serializers.LOCATION.serialize(location), 0.3);

            for (String msg : MESSAGES.getStringList("hologram")) {
                msg = msg.replace("%player%", playerName);
                msg = msg.replace("%xp%", "" + storedXP);
                msg = msg.replace("%item%", "" + countItems());
                msg = msg.replace("%despawn-time%", StringUtils.formatTime(CONFIG.getInt("despawn-time-seconds", 180) * 1_000L - (System.currentTimeMillis() - spawned)));
                hologram.addLine(StringUtils.format(msg));
            }
        });
    }

    private EulerAngle getEuler(Location dir) {
        double yaw = -Math.atan2(dir.getX(), dir.getZ()) + Math.PI / 4;
        return new EulerAngle(0, yaw, 0);
    }

    public void update() {

        int items = countItems();

        if (CONFIG.getInt("despawn-time-seconds", 180) * 1_000L <= (System.currentTimeMillis() - spawned) || items == 0) {
            remove();
            return;
        }

        int ms = MESSAGES.getStringList("hologram").size();
        for (int i = 0; i < ms; i++) {
            String msg = MESSAGES.getStringList("hologram").get(i);
            msg = msg.replace("%player%", playerName);
            msg = msg.replace("%xp%", "" + storedXP);
            msg = msg.replace("%item%", "" + items);
            msg = msg.replace("%despawn-time%", StringUtils.formatTime(CONFIG.getInt("despawn-time-seconds", 180) * 1_000L - (System.currentTimeMillis() - spawned)));

            if (i > hologram.getLines().size() - 1) {
                hologram.addLine(StringUtils.format(msg));
            } else {
                hologram.setLine(i, StringUtils.format(msg));
            }
        }
    }

    public void reload() {
        for (int i = 0; i < hologram.getLines().size(); i++) {
            hologram.removeLine(i);
        }
    }

    public int countItems() {
        int am = 0;
        for (ItemStack it : gui.getInventory().getContents()) {
            if (it == null) continue;
            am++;
        }
        return am;
    }

    public void remove() {
        SpawnedGrave.removeGrave(Grave.this);

        Scheduler.get().runAt(location, scheduledTask -> {
            removeInventory();

            entity.remove();
            hologram.remove();
        });

    }

    public void removeInventory() {
        closeInventory();

        if (CONFIG.getBoolean("drop-items", true)) {
            for (ItemStack it : gui.getInventory().getContents()) {
                if (it == null) continue;
                location.getWorld().dropItem(location.clone().add(0, -1.0, 0), it);
            }
        }

        if (storedXP == 0) return;
        final ExperienceOrb exp = (ExperienceOrb) location.getWorld().spawnEntity(location, EntityType.EXPERIENCE_ORB);
        exp.setExperience(storedXP);
    }

    private void closeInventory() {
        final List<HumanEntity> viewers = new ArrayList<>(gui.getInventory().getViewers());
        final Iterator<HumanEntity> viewerIterator = viewers.iterator();

        while (viewerIterator.hasNext()) {
            viewerIterator.next().closeInventory();
            viewerIterator.remove();
        }
    }

    public Location getLocation() {
        return location;
    }

    public OfflinePlayer getPlayer() {
        return player;
    }

    public long getSpawned() {
        return spawned;
    }

    public StorageGui getGui() {
        return gui;
    }

    public int getStoredXP() {
        return storedXP;
    }

    public PacketArmorStand getEntity() {
        return entity;
    }

    public Hologram getHologram() {
        return hologram;
    }
}