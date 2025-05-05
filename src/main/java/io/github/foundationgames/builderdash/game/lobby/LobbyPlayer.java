package io.github.foundationgames.builderdash.game.lobby;

import io.github.foundationgames.builderdash.game.lobby.ui.LobbyGui;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

public class LobbyPlayer {
    public final BDLobbyActivity<?> lobby;
    public final PlayerRef player;
    public LobbyGui gui;
    public boolean ready = false;
    private int readyUseCooldown = 20;

    public LobbyPlayer(GameSpace space, BDLobbyActivity<?> lobby, PlayerRef player) {
        this.lobby = lobby;
        this.player = player;

        player.ifOnline(space, s -> {
            this.gui = new LobbyGui(s, this);
            this.gui.open();
        });
    }

    public void tick() {
        if (this.readyUseCooldown > 0) {
            this.readyUseCooldown--;
        }
    }

    public void updateReady(boolean ready) {
        if (this.readyUseCooldown > 0) {
            return;
        }

        this.ready = ready;
        this.lobby.checkCanStart();
    }

    public void destroy() {
        this.gui.close();
    }
}
