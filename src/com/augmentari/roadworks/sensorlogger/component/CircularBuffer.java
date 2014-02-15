package com.augmentari.roadworks.sensorlogger.component;

import java.util.Arrays;

/**
 * //TODO replace me with something normal
 */
public class CircularBuffer {

    private static final int MAX_SIZE = 1024 * 10;

    int endIndex = -1;

    private int size;
    public boolean isOverflown = false;
    private float[] dataArray;

    /**
     * Default constructor, making a 'big enough' buffer to have enough data for the whole screen.
     */
    public CircularBuffer() {
        this(MAX_SIZE);
    }

    /**
     * Create circular buffer of a given size.
     *
     * @param size size of the buffer. It will overwrite the oldest entries when there's no more capacity for them.
     */
    CircularBuffer(int size) {
        dataArray = new float[size];
        Arrays.fill(dataArray, 0);

        this.size = size;
    }

    public synchronized void append(float value) {
        if (endIndex == size - 1) {
            isOverflown = true;
        }
        endIndex = ++endIndex % size;
        dataArray[endIndex] = value;
    }

    public int getActualSize() {
        if (isOverflown) {
            return size;
        }
        return endIndex + 1;
    }

    public synchronized float getValue(int i) {
        int index = endIndex - i;

        while (index < 0) {
            index += size;
        }

        return dataArray[index];
    }
}