package net.runelite.client.plugins.microbot.thieving;

import java.time.Duration;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.thieving.enums.ThievingNpc;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class ThievingOverlay extends OverlayPanel {
    private final ThievingPlugin plugin;

    @Inject
    ThievingOverlay(ThievingPlugin plugin)
    {
        super(plugin);
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    private String getDoorString(int time) {
        if (time == -1) return "Closed";
        if (time == 0) return "Closing";
        return "Closing in " + (time/1_000) + "s";
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(220, 160));

            panelComponent.getChildren().add(
                    TitleComponent.builder()
                            .text("Micro Thieving V" + ThievingPlugin.version)
                            .color(Color.ORANGE)
                            .build()
            );

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("XP:")
                            .right(String.valueOf(plugin.xpGained()))
                            .build()
            );

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("STATE:")
                            .right(plugin.getState())
                            .build()
            );

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("Stunned:")
                            .right(Rs2Player.isStunned() + "")
                            .build()
            );

            if (plugin.getConfig().shadowVeil()) {
                panelComponent.getChildren().add(
                        LineComponent.builder()
                                .left("Shadow Veil:")
                                .right(Rs2Magic.isShadowVeilActive() ? "Active" : "Inactive")
                                .build()
                );
            }

            if (plugin.getConfig().THIEVING_NPC() == ThievingNpc.VYRES) {
                panelComponent.getChildren().add(
                        LineComponent.builder()
                                .left("Door:")
                                .right(getDoorString(ThievingScript.getCloseDoorTime()))
                                .build()
                );
            }

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("RUNTIME:")
                            .right(getFormattedDuration(plugin.getRunTime()))
                            .build()
            );
        } catch (Exception ex) {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
        return super.render(graphics);
    }

	private String getFormattedDuration(Duration duration)
	{
		long hours = duration.toHours();
		long minutes = duration.toMinutes() % 60;
		long seconds = duration.getSeconds() % 60;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}
}