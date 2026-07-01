package com.jcscllc.proxiscape;

import com.google.inject.Provides;
import com.jcscllc.proxiscape.config.ProxiScapeConfig;
import com.jcscllc.proxiscape.file.FileManager;
import com.jcscllc.proxiscape.overlay.ProxiScapeOverlay;
import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;
import com.jcscllc.proxiscape.voiceclient.sound.ui.AudioTestUI;
import com.jcscllc.proxiscape.voiceclient.sound.util.Microphone;
import com.jcscllc.proxiscape.voiceclient.sound.util.Speaker;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import javax.sound.sampled.Mixer;
import java.io.FileNotFoundException;
import java.net.SocketException;

import static net.runelite.api.GameState.*;

@Slf4j
@PluginDescriptor(
        name = "Proximity Voice Chat"
)
public class ProxiScapePlugin extends Plugin {

    private static final int PUBLIC_CHAT_FILTER_ID = 10616846;

    public static Object[] PLAYER_INFO;

    @Inject
    private Client client;

    @Inject
    private ConfigManager configManager;

    @Getter
    @Inject
    private ProxiScapeConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Getter
    @Inject
    private ProxiScapeOverlay overlay;

    @Getter
    private VoiceClient voiceClient;

    @Provides
    ProxiScapeConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ProxiScapeConfig.class);
    }

    @Override
    protected void startUp() {
        if (client.getLocalPlayer() != null)
            startVoiceClient();

        overlayManager.add(overlay);

    }

    @Override
    protected void shutDown() {
        stopVoiceClient();

        overlayManager.remove(overlay);
    }

    int lastWorld = -1;
    int lastX = -1;
    int lastY = -1;

    @Subscribe
    public void onGameTick(GameTick event) {
        Player player = client.getLocalPlayer();

        if (player == null)
            return;

        int world = client.getWorld();

        WorldPoint wp = player.getWorldLocation();

        int x = wp.getX();
        int y = wp.getY();

        if (PLAYER_INFO == null) {
            try {
                voiceClient.getPacketManager().createVoiceSocket(getWorldType(world));

                voiceClient.getPacketManager().writeAuthenticatePacket(client.getAccountHash() + "", player.getName());

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        PLAYER_INFO = new Object[] {
                client.getAccountHash(),
                player.getName(),
                world,
                x,
                y,
                wp.getPlane()
        };

        if (lastWorld == world && lastX == x && lastY == y)
            return;

        lastWorld = world;
        lastX = x;
        lastY = y;

        try {
            voiceClient.getPacketManager().writeUpdatePositionPacket(client.getAccountHash() + "", world, x, y);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int getWorldType(int worldNumber) {
        for (World world : client.getWorldList())
            if (world.getId() == worldNumber)
                return world.getLocation();

        return 0;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == LOGIN_SCREEN)
            stopVoiceClient();
        else if (gameStateChanged.getGameState() == LOGGED_IN)
            startVoiceClient();
        else if (gameStateChanged.getGameState() == HOPPING)
            stopVoiceClient();
    }

    private void startVoiceClient() {
        Mixer.Info micInfo = null;
        Mixer.Info speakerInfo = null;

        try {
            micInfo = FileManager.readInfo("microphone", true);
            speakerInfo = FileManager.readInfo("speaker", false);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        if (voiceClient != null) {
            SoundManager oldSoundManager = voiceClient.getSoundManager();
            Mixer.Info oldMicInfo = oldSoundManager.getMicrophone().getMicInfo();
            Mixer.Info oldSpeakerInfo = oldSoundManager.getSpeaker().getSpkrInfo();

            if (micInfo == null && oldMicInfo != null)
                micInfo = oldMicInfo;

            if (speakerInfo == null && oldSpeakerInfo != null)
                speakerInfo = oldSpeakerInfo;
        }

        try {
            voiceClient = new VoiceClient(this);

            SoundManager newSoundManager = voiceClient.getSoundManager();

            if (micInfo != null)
                newSoundManager.getMicrophone().reconnect(micInfo);

            if (speakerInfo != null)
                newSoundManager.getSpeaker().reconnect(speakerInfo);

            voiceClient.start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    private void stopVoiceClient() {
        if (voiceClient != null && voiceClient.isRunning())
            voiceClient.end();

        PLAYER_INFO = null;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("proxiscape"))
            return;

        String key = event.getKey();

        if ((key.equals("microphone") || key.equals("speaker") || key.equals("audio")) && event.getOldValue().equals("true"))
            return;

        if (key.equals("audio")) {
            new AudioTestUI(this).startUI();
            return;
        }

        String newValue = event.getNewValue();

        if (voiceClient == null || !voiceClient.isRunning())
            return;

        SoundManager manager = voiceClient.getSoundManager();
        Microphone microphone = manager.getMicrophone();
        Speaker speaker = manager.getSpeaker();

        switch (key) {
            case "microphone" :
                if (microphone.isRunning()) {
                    microphone.end();

                    manager.setMicrophone(new Microphone(manager));
                }

                manager.getMicrophone().chooseMicrophone();
                break;

            case "speaker" :
                if (speaker.isRunning()) {
                    speaker.end();

                    manager.setSpeaker(new Speaker(manager));
                }

                manager.getSpeaker().chooseSpeaker();
                break;

            default:
                break;

        }
    }

    public boolean isPlayerIgnored(String name) {
        NameableContainer<Ignore> ignores = client.getIgnoreContainer();

        if (ignores == null)
            return false;

        if (name.equals(client.getLocalPlayer().getName()))
            return false;

        for (Nameable ignored : ignores.getMembers())
            if (ignored.getName().equalsIgnoreCase(name))
                return true;

        return false;
    }

    public boolean isPlayerFriend(String name) {
        NameableContainer<Friend> friends = client.getFriendContainer();

        if (friends == null)
            return false;

        if (name.equals(client.getLocalPlayer().getName()))
            return true;

        for (Nameable friend : friends.getMembers())
            if (friend.getName().equalsIgnoreCase(name))
                return true;

        return false;
    }

    public boolean isPublicChatFriendsOnly() {
        if (client.getLocalPlayer() == null)
            return false;

        return client.getWidget(PUBLIC_CHAT_FILTER_ID).getText().contains("Friends");
    }

    public boolean isPublicChatOff() {
        if (client.getLocalPlayer() == null)
            return false;

        return client.getWidget(PUBLIC_CHAT_FILTER_ID).getText().contains("Off");
    }

}
