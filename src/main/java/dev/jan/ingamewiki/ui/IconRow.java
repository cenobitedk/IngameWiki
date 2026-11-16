package dev.jan.ingamewiki.ui;

/**
 * Horizontal placement for the square icon buttons on the title screen and the pause screen.
 * Matches {@code TitleScreen.getHorizontalPosition}: buttons are {@value #SIZE} pixels wide with a 4 pixel gap,
 * and the row stays centered when another button is added.
 */
final class IconRow {
	static final int SIZE = 20;

	private IconRow() {
	}

	/**
	 * X positions for {@code count} buttons of {@code size}, centered on the span from {@code left} to {@code right}.
	 *
	 * @param step distance from one button's left edge to the next
	 */
	static int[] xs(int left, int right, int count, int size, int step) {
		int center = left + (right - left) / 2;
		int width = (count - 1) * step + size;
		int start = center - width / 2;
		int[] xs = new int[count];
		for (int i = 0; i < count; i++) {
			xs[i] = start + i * step;
		}
		return xs;
	}
}
