package com.jcscllc.proxiscape;

import com.google.inject.Provides;
import com.jcscllc.proxiscape.config.ProxiScapeConfig;
import com.jcscllc.proxiscape.voiceclient.VoiceClient;
import com.jcscllc.proxiscape.voiceclient.sound.SoundManager;
import com.jcscllc.proxiscape.voiceclient.sound.util.Microphone;
import com.jcscllc.proxiscape.voiceclient.sound.util.Speaker;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.net.SocketException;

import static net.runelite.api.GameState.LOGGED_IN;
import static net.runelite.api.GameState.LOGIN_SCREEN;

@Slf4j
@PluginDescriptor(
        name = "ProxiScape"
)
public class ProxiScapePlugin extends Plugin {

    public static Object[] PLAYER_INFO;

    @Inject
    private Client client;

    @Inject
    private ProxiScapeConfig config;

    private VoiceClient voiceClient;

    @Provides
    ProxiScapeConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ProxiScapeConfig.class);
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

        PLAYER_INFO = new Object[]{client.getAccountHash(), player.getName(), client.getWorld(), x, y, wp.getPlane()};

        try {
            voiceClient.getPacketManager().writePositionUpdatePacket(client.getAccountHash() + "", client.getWorld(), x, y);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {

        if (gameStateChanged.getGameState() == LOGIN_SCREEN) {
            if (voiceClient != null && voiceClient.isRunning())
                voiceClient.end();


            PLAYER_INFO = null;
        } else if (gameStateChanged.getGameState() == LOGGED_IN) {
            try {
                voiceClient = new VoiceClient();
                voiceClient.start();
            } catch (SocketException e) {
                e.printStackTrace();
            }
        }

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
        Microphone microphone = voiceClient.getSoundManager().getMicrophone();
        Speaker speaker = voiceClient.getSoundManager().getSpeaker();

        switch (key) {
            case "microphone":
                if (microphone.isRunning()) {
                    microphone.end();

                    manager.setMicrophone(new Microphone(manager));
                }

                manager.getMicrophone().captureAndStartMicrophone();
                break;

            case "speaker":
                if (speaker.isRunning()) {
                    speaker.end();

                    manager.setSpeaker(new Speaker(manager));
                }

                manager.getSpeaker().captureAndStartSpeaker();
                break;

            case "muted":
                if (newValue.equals("true"))
                    voiceClient.getSoundManager().getMicrophone().mute();
                else
                    voiceClient.getSoundManager().getMicrophone().unmute();
                break;

            case "deafened":
                if (voiceClient != null)
                    if (newValue.equals("true"))
                        voiceClient.getSoundManager().getSpeaker().deafen();
                    else
                        voiceClient.getSoundManager().getSpeaker().undeafen();
            default:
                break;

        }
    }
}
