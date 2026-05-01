package com.jcscllc.proxiscape;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ProxiScapePluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(ProxiScapePlugin.class);
        RuneLite.main(args);
    }
}