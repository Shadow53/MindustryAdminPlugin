package com.shadow53;

import arc.*;
import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.*;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.JsonValue.ValueType;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.JsonIO;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.maps.Maps.ShuffleMode;
import mindustry.mod.*;
import mindustry.mod.Mods.LoadedMod;
import mindustry.net.Administration.*;
import mindustry.net.Packets.KickReason;
import mindustry.type.Item;
import mindustry.world.blocks.storage.*;

public class AutoPausePlugin extends Plugin{
    private void sendMessage(Player player, String fmt, Object ... tokens) {
        player.sendMessage(String.format(fmt, tokens));
    }

    private void err(Player player, String fmt, Object ... msg) {
        sendMessage(player, "[scarlet]Error: " + fmt, msg);
    }

    private void info(Player player, String fmt, Object ... msg) {
        sendMessage(player, "Info: " + fmt, msg);
    }

    //register commands that run on the client
    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("admin", "<add/remove> <username/ID...>", "Make an online user admin", (arg, player) -> {
            if(!Vars.state.is(State.playing)){
                err(player, "Open the server first.");
                return;
            }

            if(!(arg[0].equals("add") || arg[0].equals("remove"))){
                err(player, "Second parameter must be either 'add' or 'remove'.");
                return;
            }

            boolean add = arg[0].equals("add");

            PlayerInfo target;
            Player playert = Groups.player.find(p -> p.name.equalsIgnoreCase(arg[1]));
            if(playert != null){
                target = playert.getInfo();
            }else{
                target = Vars.netServer.admins.getInfoOptional(arg[1]);
                playert = Groups.player.find(p -> p.getInfo() == target);
            }

            if(target != null){
                if(add){
                    Vars.netServer.admins.adminPlayer(target.id, target.adminUsid);
                }else{
                    Vars.netServer.admins.unAdminPlayer(target.id);
                }
                if(playert != null) playert.admin = add;
                info(player, "Changed admin status of player: @", target.lastName);
            }else{
                err(player, "Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.");
            }
        });

        handler.<Player>register("admins", "List all admins", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            Seq<PlayerInfo> admins = Vars.netServer.admins.getAdmins();

            if(admins.size == 0){
                info(player, "No admins have been found.");
            }else{
                info(player, "Admins:");
                for(PlayerInfo info : admins){
                    info(player, " &lm @ /  ID: '@' / IP: '@'", info.lastName, info.id, info.lastIP);
                }
            }
        });

        handler.<Player>register("ban", "<type-id/name/ip> <username/IP/ID...>", "Ban a person", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(arg[0].equals("id")){
                Vars.netServer.admins.banPlayerID(arg[1]);
                info(player, "Banned.");
            }else if(arg[0].equals("name")){
                Player target = Groups.player.find(p -> p.name().equalsIgnoreCase(arg[1]));
                if(target != null){
                    Vars.netServer.admins.banPlayer(target.uuid());
                    info(player, "Banned.");
                }else{
                    err(player, "No matches found.");
                }
            }else if(arg[0].equals("ip")){
                Vars.netServer.admins.banPlayerIP(arg[1]);
                info(player, "Banned.");
            }else{
                err(player, "Invalid type.");
            }

            for(Player gPlayer : Groups.player){
                if(Vars.netServer.admins.isIDBanned(gPlayer.uuid())){
                    Call.sendMessage("[scarlet]" + gPlayer.name + " has been banned.");
                    gPlayer.con.kick(KickReason.banned);
                }
            }
        });

        handler.<Player>register("bans", "List all bans", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            Seq<PlayerInfo> bans = Vars.netServer.admins.getBanned();

            if(bans.size == 0){
                info(player, "No ID-banned players have been found.");
            }else{
                info(player, "Banned players [ID]:");
                for(PlayerInfo info : bans){
                    info(player, " @ / Last known name: '@'", info.id, info.lastName);
                }
            }

            Seq<String> ipbans = Vars.netServer.admins.getBannedIPs();

            if(ipbans.size == 0){
                info(player, "No IP-banned players have been found.");
            }else{
                info(player, "Banned players [IP]:");
                for(String string : ipbans){
                    PlayerInfo info = Vars.netServer.admins.findByIP(string);
                    if(info != null){
                        info(player, "  '@' / Last known name: '@' / ID: '@'", string, info.lastName, info.id);
                    }else{
                        info(player, "  '@' (No known name or info)", string);
                    }
                }
            }
        });

        handler.<Player>register("fillitems", "[team]", "Fill the core with items", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(!Vars.state.is(State.playing)){
                err(player, "Not playing. Host first.");
                return;
            }

            Team team = arg.length == 0 ? Team.sharded : Structs.find(Team.all, t -> t.name.equals(arg[0]));

            if(team == null){
                err(player, "No team with that name found.");
                return;
            }

            if(Vars.state.teams.cores(team).isEmpty()){
                err(player, "That team has no cores.");
                return;
            }

            for(Item item : Vars.content.items()){
                Vars.state.teams.cores(team).first().items.set(item, Vars.state.teams.cores(team).first().storageCapacity);
            }

            info(player, "Core filled.");
        });

        //handler.<Player>register("gameover", "Force a game over", (arg, player) -> {
        //    if(Vars.state.isMenu()){
        //        err("Not playing a map.");
        //        return;
        //    }

        //    info("Core destroyed.");
        //    inExtraRound = false;
        //    Events.fire(new GameOverEvent(Team.crux));
        //});

        handler.<Player>register("info", "<IP/UUID/name...>", "Find player info", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            ObjectSet<PlayerInfo> infos = Vars.netServer.admins.findByName(arg[0]);

            if(infos.size > 0){
                info(player, "Players found: @", infos.size);

                int i = 0;
                for(PlayerInfo info : infos){
                    info(player, "[@] Trace info for player '@' / UUID @", i++, info.lastName, info.id);
                    info(player, "  all names used: @", info.names);
                    info(player, "  IP: @", info.lastIP);
                    info(player, "  all IPs used: @", info.ips);
                    info(player, "  times joined: @", info.timesJoined);
                    info(player, "  times kicked: @", info.timesKicked);
                }
            }else{
                info(player, "Nobody with that name could be found.");
            }
        });

        handler.<Player>register("kick", "<username...>", "Kick a person by name", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(!Vars.state.is(State.playing)){
                err(player, "Not hosting a game yet. Calm down.");
                return;
            }

            Player target = Groups.player.find(p -> p.name().equals(arg[0]));

            if(target != null){
                Call.sendMessage("[scarlet]" + target.name() + "[scarlet] has been kicked by the server.");
                target.kick(KickReason.kick);
                info(player, "It is done.");
            }else{
                info(player, "Nobody with that name could be found...");
            }
        });

        handler.<Player>register("load", "<slot>", "Load a save from a slot", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(Vars.state.is(State.playing)){
                err(player, "Already hosting. Type 'stop' to stop hosting first.");
                return;
            }

            Fi file = Vars.saveDirectory.child(arg[0] + "." + Vars.saveExtension);

            if(!SaveIO.isSaveValid(file)){
                err(player, "No (valid) save data found for slot.");
                return;
            }

            Core.app.post(() -> {
                try{
                    SaveIO.load(file);
                    Vars.state.rules.sector = null;
                    info(player, "Save loaded.");
                    Vars.state.set(State.playing);
                    Vars.netServer.openServer();
                }catch(Throwable t){
                    err(player, "Failed to load save. Outdated or corrupt file.");
                }
            });
        });

        handler.<Player>register("maps", "List all maps on server", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(!Vars.maps.all().isEmpty()){
                info(player, "Maps:");
                for(Map map : Vars.maps.all()){
                    info(player, "  @: &fi@ / @x@", map.name(), map.custom ? "Custom" : "Default", map.width, map.height);
                }
            }else{
                info(player, "No maps found.");
            }
            info(player, "Map directory: &fi@", Vars.customMapDirectory.file().getAbsoluteFile().toString());
        });

        handler.<Player>register("mod", "<name>", "Display information about a loaded plugin", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            LoadedMod mod = Vars.mods.list().find(p -> p.meta.name.equalsIgnoreCase(arg[0]));
            if(mod != null){
                info(player, "Name: @", mod.meta.displayName());
                info(player, "Internal Name: @", mod.name);
                info(player, "Version: @", mod.meta.version);
                info(player, "Author: @", mod.meta.author);
                info(player, "Path: @", mod.file.path());
                info(player, "Description: @", mod.meta.description);
            }else{
                info(player, "No mod with name '@' found.", arg[0]);
            }
        });

        handler.<Player>register("mods", "Display all loaded mods", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(!Vars.mods.list().isEmpty()){
                info(player, "Mods:");
                for(LoadedMod mod : Vars.mods.list()){
                    info(player, "  @ &fi@", mod.meta.displayName(), mod.meta.version);
                }
            }else{
                info(player, "No mods found.");
            }
            info(player, "Mod directory: &fi@", Vars.modDirectory.file().getAbsoluteFile().toString());
        });

        //handler.<Player>register("nextmap", "<mapname...>", "Set the next map to be played after a game-over; overrides shuffling", (arg, player) -> {
        //    Map res = Vars.maps.all().find(map -> map.name().equalsIgnoreCase(arg[0].replace('_', ' ')) || map.name().equalsIgnoreCase(arg[0]));
        //    if(res != null){
        //        Vars.netServer.nextMapOverride = res;
        //        info("Next map set to '@'.", res.name());
        //    }else{
        //        err("No map '@' found.", arg[0]);
        //    }
        //});

        handler.<Player>register("pardon", "<ID>", "Pardon a votekicked player by ID and allow them to join again", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            PlayerInfo info = Vars.netServer.admins.getInfoOptional(arg[0]);

            if(info != null){
                info.lastKicked = 0;
                info(player, "Pardoned player: @", info.lastName);
            }else{
                err(player, "That ID can't be found.");
            }
        });

        handler.<Player>register("pause", "Pause or unpause the game", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            boolean pause = arg[0].equals("on");
            Vars.state.serverPaused = pause;
            info(player, pause ? "Game paused." : "Game unpaused.");
        });

        handler.<Player>register("players", "List all players currently in game", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(Groups.player.size() == 0){
                info(player, "No players are currently in the server.");
            }else{
                info(player, "Players: @", Groups.player.size());
                for(Player user : Groups.player){
                    PlayerInfo userInfo = user.getInfo();
                    info(player, " &lm @ /  ID: @ / IP: @ / Admin: @", userInfo.lastName, userInfo.id, userInfo.lastIP, userInfo.admin);
                }
            }
        });

        handler.<Player>register("playerlimit", "[off/somenumber]", "Set the server player limit", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(arg.length == 0){
                info(player, "Player limit is currently @.", Vars.netServer.admins.getPlayerLimit() == 0 ? "off" : Vars.netServer.admins.getPlayerLimit());
                return;
            }
            if(arg[0].equals("off")){
                Vars.netServer.admins.setPlayerLimit(0);
                info(player, "Player limit disabled.");
                return;
            }

            if(Strings.canParsePositiveInt(arg[0]) && Strings.parseInt(arg[0]) > 0){
                int lim = Strings.parseInt(arg[0]);
                Vars.netServer.admins.setPlayerLimit(lim);
                info(player, "Player limit is now &lc@.", lim);
            }else{
                err(player, "Limit must be a number above 0.");
            }
        });

        handler.<Player>register("reloadmaps", "List all maps on server", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            int beforeMaps = Vars.maps.all().size;
            Vars.maps.reload();
            if(Vars.maps.all().size > beforeMaps){
                info(player, "@ new map(s) found and reloaded.", Vars.maps.all().size - beforeMaps);
            }else{
                info(player, "Maps reloaded.");
            }
        });

        handler.<Player>register("rules", "[remove/add] [name] [value...]", "List, remove, or add global rules; apply regardless of map", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            String rules = Core.settings.getString("globalrules");
            JsonValue base = JsonIO.json().fromJson(null, rules);

            if(arg.length == 0){
                info(player, "Rules:\n@", JsonIO.print(rules));
            }else if(arg.length == 1){
                err(player, "Invalid usage. Specify which rule to remove or add.");
            }else{
                if(!(arg[0].equals("remove") || arg[0].equals("add"))){
                    err(player, "Invalid usage. Either add or remove rules.");
                    return;
                }

                boolean remove = arg[0].equals("remove");
                if(remove){
                    if(base.has(arg[1])){
                        info(player, "Rule '@' removed.", arg[1]);
                        base.remove(arg[1]);
                    }else{
                        err(player, "Rule not defined, so not removed.");
                        return;
                    }
                }else{
                    if(arg.length < 3){
                        err(player, "Missing last argument. Specify which value to set the rule to.");
                        return;
                    }

                    try{
                        JsonValue value = new JsonReader().parse(arg[2]);
                        value.name = arg[1];

                        JsonValue parent = new JsonValue(ValueType.object);
                        parent.addChild(value);

                        JsonIO.json().readField(Vars.state.rules, value.name, parent);
                        if(base.has(value.name)){
                            base.remove(value.name);
                        }
                        base.addChild(arg[1], value);
                        info(player, "Changed rule: @", value.toString().replace("\n", " "));
                    }catch(Throwable e){
                        err(player, "Error parsing rule JSON: @", e.getMessage());
                    }
                }

                Core.settings.put("globalrules", base.toString());
                Call.setRules(Vars.state.rules);
            }
        });

        handler.<Player>register("runwave", "Trigger the next wave", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(!Vars.state.is(State.playing)){
                err(player, "Not hosting. Host a game first.");
            }else{
                Vars.logic.runWave();
                info(player, "Wave spawned.");
            }
        });

        handler.<Player>register("save", "<slot>", "Save game state to a slot", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(!Vars.state.is(State.playing)){
                err(player, "Not hosting. Host a game first.");
                return;
            }

            Fi file = Vars.saveDirectory.child(arg[0] + "." + Vars.saveExtension);

            Core.app.post(() -> {
                SaveIO.save(file);
                info(player, "Saved to @.", file);
            });
        });

        handler.<Player>register("saves", "List all saves in the save directory", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            info(player, "Save files: ");
            for(Fi file : Vars.saveDirectory.list()){
                if(file.extension().equals(Vars.saveExtension)){
                    info(player, "| @", file.nameWithoutExtension());
                }
            }
        });

        handler.<Player>register("shuffle", "[none/all/custom/builtin]", "Set map shuffling mode", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(arg.length == 0){
                info(player, "Shuffle mode current set to '@'.", Vars.maps.getShuffleMode());
            }else{
                try{
                    ShuffleMode mode = ShuffleMode.valueOf(arg[0]);
                    Core.settings.put("shufflemode", mode.name());
                    Vars.maps.setShuffleMode(mode);
                    info(player, "Shuffle mode set to '@'.", arg[0]);
                }catch(Exception e){
                    err(player, "Invalid shuffle mode.");
                }
            }
        });

        handler.<Player>register("unban", "<ip/ID>", "Unban a person", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(Vars.netServer.admins.unbanPlayerIP(arg[0]) || Vars.netServer.admins.unbanPlayerID(arg[0])){
                info(player, "Unbanned player: @", arg[0]);
            }else{
                err(player, "That IP/ID is not banned!");
            }
        });

        handler.<Player>register("whitelisted", "List the entire whitelist", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            if(Vars.netServer.admins.getWhitelisted().isEmpty()){
                info(player, "No whitelisted players found.");
                return;
            }

            info(player, "Whitelist:");
            Vars.netServer.admins.getWhitelisted().each(p -> info(player, "- @", p.lastName));
        });

        handler.<Player>register("whitelist-add", "<ID>", "Add a player to the whitelist by ID", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            PlayerInfo info = Vars.netServer.admins.getInfoOptional(arg[0]);
            if(info == null){
                err(player, "Player ID not found. You must use the ID displayed when a player joins a server.");
                return;
            }

            Vars.netServer.admins.whitelist(arg[0]);
            info(player, "Player '@' has been whitelisted.", info.lastName);
        });

        handler.<Player>register("whitelist-remove", "<ID>", "Remove a player from the whitelist by ID", (arg, player) -> {
            if (!player.admin()) {
                return;
            }

            PlayerInfo info = Vars.netServer.admins.getInfoOptional(arg[0]);
            if(info == null){
                err(player, "Player ID not found. You must use the ID displayed when a player joins a server.");
                return;
            }

            Vars.netServer.admins.unwhitelist(arg[0]);
            info(player, "Player '@' has been un-whitelisted.", info.lastName);
        });
    }
}
