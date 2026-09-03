package dev.ae2gateway;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

final class GatewaySavedData extends SavedData {
    static final String NAME = "ae2_gateway";
    final Map<UUID, Enrollment> grids = new HashMap<>();
    final Map<String, IdentityLink> identities = new HashMap<>();

    static GatewaySavedData load(CompoundTag root) {
        GatewaySavedData data = new GatewaySavedData();
        ListTag grids = root.getList("Grids", Tag.TAG_COMPOUND);
        for (int i = 0; i < grids.size(); i++) {
            CompoundTag tag = grids.getCompound(i);
            try {
                UUID id = tag.getUUID("Id");
                Enrollment enrollment = new Enrollment(id, tag.getString("Alias"), tag.getString("Dimension"),
                        tag.getLong("Position"), tag.getString("Side"));
                ListTag acl = tag.getList("Acl", Tag.TAG_COMPOUND);
                for (int j = 0; j < acl.size(); j++) {
                    CompoundTag entry = acl.getCompound(j);
                    enrollment.acl.put(entry.getUUID("Player"), entry.getInt("Permissions"));
                }
                data.grids.put(id, enrollment);
            } catch (RuntimeException ignored) {
                // A malformed individual record must not prevent the world from loading.
            }
        }
        ListTag links = root.getList("Identities", Tag.TAG_COMPOUND);
        for (int i = 0; i < links.size(); i++) {
            CompoundTag tag = links.getCompound(i);
            try {
                String subject = tag.getString("Subject");
                data.identities.put(subject, new IdentityLink(subject, tag.getString("Email"),
                        tag.getUUID("Player"), tag.getString("PlayerName")));
            } catch (RuntimeException ignored) {
                // Fail closed by dropping malformed identity links.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag gridList = new ListTag();
        for (Enrollment enrollment : grids.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", enrollment.id);
            tag.putString("Alias", enrollment.alias);
            tag.putString("Dimension", enrollment.dimension);
            tag.putLong("Position", enrollment.position);
            tag.putString("Side", enrollment.side);
            ListTag acl = new ListTag();
            for (Map.Entry<UUID, Integer> permission : enrollment.acl.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("Player", permission.getKey());
                entry.putInt("Permissions", permission.getValue());
                acl.add(entry);
            }
            tag.put("Acl", acl);
            gridList.add(tag);
        }
        root.put("Grids", gridList);

        ListTag linkList = new ListTag();
        for (IdentityLink link : identities.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Subject", link.subject);
            tag.putString("Email", link.email);
            tag.putUUID("Player", link.player);
            tag.putString("PlayerName", link.playerName);
            linkList.add(tag);
        }
        root.put("Identities", linkList);
        return root;
    }

    static final class Enrollment {
        static final int READ = 1;
        static final int CRAFT = 2;

        final UUID id;
        String alias;
        String dimension;
        long position;
        String side;
        final Map<UUID, Integer> acl = new HashMap<>();

        Enrollment(UUID id, String alias, String dimension, long position, String side) {
            this.id = id;
            this.alias = alias;
            this.dimension = dimension;
            this.position = position;
            this.side = side;
        }

        boolean permits(UUID player, int permission) {
            int granted = acl.getOrDefault(player, 0);
            return (granted & permission) == permission;
        }
    }

    record IdentityLink(String subject, String email, UUID player, String playerName) {
    }
}
