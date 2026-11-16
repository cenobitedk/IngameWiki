package dev.jan.ingamewiki.ui;

import dev.jan.ingamewiki.IngameWiki;
import dev.jan.ingamewiki.WikiOpener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Book button in the icon row on the title screen and the pause screen, in the same row as Mod Menu.
 */
public final class WikiMenuButton extends SpriteIconButton.CenteredIcon {
	private static final Identifier SPRITE = Identifier.fromNamespaceAndPath(IngameWiki.MOD_ID, "icon/wiki");

	private WikiMenuButton(Screen parent) {
		super(
				IconRow.SIZE,
				IconRow.SIZE,
				Component.translatable("ingamewiki.title"),
				14,
				14,
				0,
				0,
				new WidgetSprites(SPRITE),
				button -> WikiOpener.open(WikiOpener.lastPage(), parent),
				Component.translatable("ingamewiki.menu.tooltip"),
				null,
				false);
	}

	/**
	 * Adds the button to the icon row and recenters that row so the new button fits.
	 * Does nothing on screens without that row.
	 */
	public static void add(Screen screen) {
		if (!(screen instanceof TitleScreen) && !(screen instanceof PauseScreen)) {
			return;
		}
		List<AbstractWidget> widgets = new ArrayList<>(Screens.getWidgets(screen));
		for (AbstractWidget widget : widgets) {
			if (widget instanceof WikiMenuButton) {
				return;
			}
		}
		List<AbstractWidget> row = iconRow(widgets);
		if (row.size() < 2) {
			return;
		}
		row.sort(Comparator.comparingInt(AbstractWidget::getX));
		int step = row.get(1).getX() - row.get(0).getX();
		if (step <= IconRow.SIZE) {
			return;
		}
		int y = row.get(0).getY();
		AbstractWidget last = row.get(row.size() - 1);
		int[] xs = IconRow.xs(row.get(0).getX(), last.getX() + last.getWidth(), row.size() + 1, IconRow.SIZE, step);
		WikiMenuButton wiki = new WikiMenuButton(screen);
		for (int i = 0; i < row.size(); i++) {
			row.get(i).setPosition(xs[i], y);
		}
		wiki.setPosition(xs[xs.length - 1], y);
		List<AbstractWidget> buttons = Screens.getWidgets(screen);
		int index = buttons.indexOf(last);
		if (index >= 0) {
			buttons.add(index + 1, wiki);
		} else {
			buttons.add(wiki);
		}
	}

	/**
	 * The row of square icon buttons. Several rows can contain a button of that size (the dev "TW" button is one),
	 * so this is the largest row, and the lower one when two rows are the same size.
	 */
	private static List<AbstractWidget> iconRow(List<AbstractWidget> widgets) {
		Map<Integer, List<AbstractWidget>> byY = new TreeMap<>();
		for (AbstractWidget widget : widgets) {
			if (widget.getWidth() == IconRow.SIZE && widget.getHeight() == IconRow.SIZE) {
				byY.computeIfAbsent(widget.getY(), y -> new ArrayList<>()).add(widget);
			}
		}
		List<AbstractWidget> best = null;
		// TreeMap walks top to bottom, so a tie keeps the lower row.
		for (List<AbstractWidget> row : byY.values()) {
			if (best == null || row.size() >= best.size()) {
				best = row;
			}
		}
		return best != null && best.size() >= 2 ? new ArrayList<>(best) : List.of();
	}
}
