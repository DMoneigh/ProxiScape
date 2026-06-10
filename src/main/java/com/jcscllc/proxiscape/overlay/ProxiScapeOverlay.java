package com.jcscllc.proxiscape.overlay;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ProxiScapeOverlay extends Overlay {

    private final Client client;

    private Map<String, Long> spoke;

    @Inject
    public ProxiScapeOverlay(Client client) {
        this.client = client;

        spoke = new ConcurrentHashMap<String, Long>();

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    public void spoke(String name) {
        spoke.put(name, System.currentTimeMillis());
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        for (Player player : client.getPlayers()) {
            if (player == null || player == client.getLocalPlayer())
                continue;

            if (!shouldMark(player))
                continue;

            drawCircle(graphics, player);
        }

        return null;
    }

    private boolean shouldMark(Player player) {
        String name = player.getName();

        return spoke.get(name) != null && System.currentTimeMillis() - spoke.get(name) < 1000;
    }

    private void drawCircle(Graphics2D graphics, Player player) {
        LocalPoint lp = player.getLocalLocation();

        if (lp == null)
            return;

        Point canvasPoint = Perspective.localToCanvas(
                client,
                lp,
                client.getPlane(),
                player.getLogicalHeight() + 20
        );

        if (canvasPoint == null)
            return;

        int radius = 5;

        graphics.setColor(Color.GREEN);
        graphics.setStroke(new BasicStroke(3));
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        // Draw circle centered on head
        graphics.drawOval(
                canvasPoint.getX() - radius,
                canvasPoint.getY() - radius,
                radius * 2,
                radius * 2
        );
    }
}