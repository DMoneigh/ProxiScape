package com.jcscllc.proxiscape.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("proxiscape")
public interface ProxiScapeConfig extends Config {

    @ConfigSection(
            name = "Voice Setup",
            description = "Select microphone and speaker.",
            position = 1
    )
    String voiceSetup = "voiceSetup";

    @ConfigSection(
            name = "Voice Control",
            description = "Mute microphone or deafen speaker.",
            position = 2
    )
    String voiceControl = "voiceControl";

    @ConfigSection(
            name = "Audio Test",
            description = "Test audio before voice setup",
            position = 3
    )
    String audioTest = "audioTest";

    @ConfigItem(
            keyName = "microphone",
            name = "Set Microphone",
            description = "Checking this sets microphone.",
            section = voiceSetup
    )
    default boolean microphone() {
        return false;
    }

    @ConfigItem(
            keyName = "speaker",
            name = "Set Speaker",
            description = "Checking this sets speaker.",
            section = voiceSetup
    )
    default boolean speaker() {
        return false;
    }

    @ConfigItem(
            keyName = "muted",
            name = "Mute Microphone",
            description = "If checked, people cannot hear you.",
            position = 1,
            section = voiceControl
    )
    default boolean muted() {
        return false;
    }

    @ConfigItem(
            keyName = "deafened",
            name = "Deafen Speaker",
            description = "If checked, you cannot hear people.",
            position = 2,
            section = voiceControl
    )
    default boolean deafened() {
        return false;
    }

    @ConfigItem(
            keyName = "audio",
            name = "Audio Test",
            description = "Click this to test audio",
            section = audioTest
    )
    default boolean audio() { return false; }

}
