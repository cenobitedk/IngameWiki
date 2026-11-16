package dev.jan.ingamewiki.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class IconRowTest {
	/**
	 * Vanilla lays the title-screen icons out with {@code TitleScreen.getHorizontalPosition}:
	 * {@code width/2 - total/2 + index * 24}, where {@code total = count * 20 + (count - 1) * 4}.
	 */
	@Test
	void addingAButtonKeepsTheRowCentered() {
		assertArrayEquals(new int[] {167, 191, 215, 239}, placed(427, 3));
		assertArrayEquals(new int[] {154, 178, 202, 226}, placed(400, 3));
		assertArrayEquals(new int[] {142, 166, 190, 214, 238}, placed(400, 4));
	}

	private static int[] placed(int screenWidth, int existing) {
		int[] current = vanilla(screenWidth, existing);
		int left = current[0];
		int right = current[current.length - 1] + IconRow.SIZE;
		return IconRow.xs(left, right, existing + 1, IconRow.SIZE, 24);
	}

	private static int[] vanilla(int screenWidth, int count) {
		int total = count * IconRow.SIZE + (count - 1) * 4;
		int start = screenWidth / 2 - total / 2;
		int[] xs = new int[count];
		for (int i = 0; i < count; i++) {
			xs[i] = start + i * 24;
		}
		return xs;
	}
}
