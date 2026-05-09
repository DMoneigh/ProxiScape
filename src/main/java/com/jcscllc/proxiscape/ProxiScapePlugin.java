package com.jcscllc.proxiscape;

import com.google.inject.Provides;
import com.jcscllc.proxiscape.config.ProxiScapeConfig;
import com.jcscllc.proxiscape.file.FileManager;
import com.jcscllc.proxiscape.overlay.ProxiScapeOverlay;
import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;
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

import static net.runelite.api.GameState.LOGGED_IN;
import static net.runelite.api.GameState.LOGIN_SCREEN;

@Slf4j
@PluginDescriptor(
        name = "Proximity Voice Chat"
)
public class ProxiScapePlugin extends Plugin {

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

    @Subscribe
    public void onGameTick(GameTick event) {

        Player player = client.getLocalPlayer();

        if (player == null)
            return;

        WorldPoint wp = player.getWorldLocation();

        int x = wp.getX();
        int y = wp.getY();

        if (PLAYER_INFO == null) {
            try {
                voiceClient.getPacketManager().writeAuthenticatePacket(client.getAccountHash() + "", player.getName());

                voiceClient.getKeepAlive().start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        PLAYER_INFO = new Object[]{
                client.getAccountHash(),
                player.getName(),
                client.getWorld(),
                x,
                y,
                wp.getPlane()
        };

        try {
            voiceClient.getPacketManager().writeUpdatePositionPacket(client.getAccountHash() + "", client.getWorld(), x, y);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {

        if (gameStateChanged.getGameState() == LOGIN_SCREEN)
            stopVoiceClient();
        else if (gameStateChanged.getGameState() == LOGGED_IN)
            startVoiceClient();
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

            if (micInfo == null && oldSoundManager.getMicrophone().getMicInfo() != null)
                micInfo = oldSoundManager.getMicrophone().getMicInfo();

            if (speakerInfo == null && oldSoundManager.getSpeaker().getSpkrInfo() != null)
                speakerInfo = oldSoundManager.getSpeaker().getSpkrInfo();
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

        if ((key.equals("microphone") || key.equals("speaker")) && event.getOldValue().equals("true"))
            return;

        String newValue = event.getNewValue();

        if (voiceClient == null || !voiceClient.isRunning())
            return;

        SoundManager manager = voiceClient.getSoundManager();
        Microphone microphone = manager.getMicrophone();
        Speaker speaker = manager.getSpeaker();

        switch (key) {
            case "microphone":
                if (microphone.isRunning()) {
                    microphone.end();

                    manager.setMicrophone(new Microphone(manager));
                }

                manager.getMicrophone().chooseMicrophone();
                break;

            case "speaker":
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

        for (Nameable ignored : ignores.getMembers())
            if (ignored.getName().equalsIgnoreCase(name))
                return true;

        return false;
    }

}
