package com.anysoftkeyboard.gesturetyping;

import android.support.annotation.NonNull;
import android.util.Log;
import android.util.SparseArray;

import com.anysoftkeyboard.base.utils.Logger;
import com.anysoftkeyboard.dictionaries.Dictionary;
import com.anysoftkeyboard.keyboards.Keyboard;
import com.anysoftkeyboard.rx.RxSchedulers;
import com.menny.android.anysoftkeyboard.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.subjects.ReplaySubject;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.pow;
import static java.lang.Math.sqrt;

public abstract class GestureTypingDetector {
    private static final String TAG = "ASKGestureTypingDetector";

    /**
     * The gesture currently being traced by the user.
     */
    protected final GestureTypingDetector.Gesture mUserGesture =
            new GestureTypingDetector.Gesture();
    /**
     * The square of the minimum distance between two points in the user gesture.
     */
    private final int mMinPointDistanceSquared;

    @NonNull
    protected final Iterable<Keyboard.Key> mKeys;
    @NonNull
    protected final SparseArray<Keyboard.Key> mKeysByCharacter = new SparseArray<>();

    @NonNull
    protected List<char[][]> mWords = Collections.emptyList();
    @NonNull
    protected List<int[]> mWordFrequencies = Collections.emptyList();

    @NonNull
    Disposable mGeneratingDisposable = Disposables.empty();
    protected int mMaxSuggestions;
    protected final ArrayList<String> mCandidates;
    protected final ArrayList<Double> mCandidateWeights;

    public enum LoadingState {
        NOT_LOADED,
        LOADING,
        LOADED
    }

    protected final ReplaySubject<GestureTypingDetector.LoadingState> mGenerateStateSubject =
            ReplaySubject.createWithSize(1);

    public GestureTypingDetector(
            int maxSuggestions, int minPointDistance, @NonNull Iterable<Keyboard.Key> keys) {
        mMaxSuggestions = maxSuggestions;
        mCandidates = new ArrayList<>(mMaxSuggestions * 3);
        mCandidateWeights = new ArrayList<>(mMaxSuggestions * 3);
        mMinPointDistanceSquared = minPointDistance * minPointDistance;
        mKeys = keys;
        for (Keyboard.Key key : mKeys) {

            Log.d("GESTUREKEYSIZE", Double.toString(key.height));
            Log.d("GESTUREKEYSIZE", Double.toString(key.width));
            for (int i = 0; i < key.getCodesCount(); ++i) {
                char c = (char) key.getCodeAtIndex(i, false);
                c = Character.toLowerCase(c);
                mKeysByCharacter.put(c, key);
            }
        }
        mGenerateStateSubject.onNext(GestureTypingDetector.LoadingState.NOT_LOADED);
    }

    @NonNull
    public Observable<GestureTypingDetector.LoadingState> state() {
        return mGenerateStateSubject;
    }

    /**
     * Sets the list of detectable words and their frequencies.
     *
     * @param words
     * @param wordFrequencies
     */
    public void setWords(@NonNull List<char[][]> words, @NonNull List<int[]> wordFrequencies) {
        mGeneratingDisposable.dispose();
        mGenerateStateSubject.onNext(LoadingState.LOADING);
        mWords = words;
        mWordFrequencies = wordFrequencies;
        mGeneratingDisposable =
                generateGestureData()
                        .subscribeOn(RxSchedulers.background())
                        .observeOn(RxSchedulers.mainThread())
                        .subscribe(mGenerateStateSubject::onNext, mGenerateStateSubject::onError);
    }

    protected abstract Observable<LoadingState> generateGestureData();

    public void destroy() {
        mGeneratingDisposable.dispose();
        mGenerateStateSubject.onNext(LoadingState.NOT_LOADED);
        mGenerateStateSubject.onComplete();
    }

    public abstract ArrayList<String> getCandidates();

    /**
     * Adds a point to the user's gesture as long as it's not too far from the previous point.
     *
     * @param x
     * @param y
     */
    public void addPoint(double x, double y) {
        if (mGenerateStateSubject.getValue() != LoadingState.LOADED) return;

        if (mUserGesture.mCurrentLength > 0) {
            int previousPointIndex = mUserGesture.mCurrentLength - 1;
            final double dx = mUserGesture.mXs[previousPointIndex] - x;
            final double dy = mUserGesture.mYs[previousPointIndex] - y;

            if (dx * dx + dy * dy <= mMinPointDistanceSquared) return;
        }

        mUserGesture.addPoint(x, y);
    }

    public void clearGesture() {
        mUserGesture.reset();
    }

    /**
     * Calculates the euclidean distance between two 2D points.
     *
     * @param x1
     * @param y1
     * @param x2
     * @param y2
     * @return The distance.
     */
    protected static double euclideanDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
    }

    /**
     * Represents a gesture, either traced by a user or generated from a known words.
     */
    protected static class Gesture {
        static final int MAX_LENGTH = 2048;
        private int mCurrentLength = 0;
        private final double[] mXs = new double[MAX_LENGTH];
        private final double[] mYs = new double[MAX_LENGTH];

        void reset() {
            mCurrentLength = 0;
        }

        /**
         * Adds a point to the gesture's path.
         *
         * @param x
         * @param y
         */
        void addPoint(double x, double y) {
            if (MAX_LENGTH == mCurrentLength) {
                if (BuildConfig.TESTING_BUILD) {
                    Logger.w(TAG, "Discarding gesture");
                }
                return;
            }

            mXs[mCurrentLength] = x;
            mYs[mCurrentLength] = y;
            mCurrentLength++;
        }

        int getCurrentLength() {
            return mCurrentLength;
        }

        double getX(int index) {
            return mXs[index];
        }

        double getY(int index) {
            return mYs[index];
        }

        double getFirstX() {
            return mXs[0];
        }

        double getFirstY() {
            return mYs[0];
        }

        double getLastX() {
            return mXs[mCurrentLength - 1];
        }

        double getLastY() {
            return mYs[mCurrentLength - 1];
        }

        double getLength() {
            double currentX;
            double currentY;
            double previousX;
            double previousY;
            double length;

            length = 0;
            for (int i = 1; i < mCurrentLength; i++) {
                previousX = mXs[i - 1];
                previousY = mYs[i - 1];

                currentX = mXs[i];
                currentY = mYs[i];

                length += euclideanDistance(previousX, previousY, currentX, currentY);
            }

            return length;
        }

        /**
         * Resamples the gesture into a new gesture with the chosen number of points by oversampling
         * it.
         *
         * @param numPoints The number of points that the new gesture will have. Must be superior to
         *                  the number of points in the current gesture.
         * @return An oversampled copy of the gesture.
         */
        public Gesture resample(int numPoints) {
            if (numPoints <= mCurrentLength) {
                throw new IllegalArgumentException();
            }
            int indexOfClosest;
            double dx;
            double dy;
            double newX;
            double newY;
            double norm;

            double interpointDistance = getLength() / numPoints;

            Gesture resampledGesture = new Gesture();
            resampledGesture.addPoint(mXs[0], mYs[0]);

            double lastX = mXs[0];
            double lastY = mYs[0];

            for (int i = 0; i < numPoints - 1; i++) {
                indexOfClosest = (i * (mCurrentLength - 2) + numPoints / 2) / numPoints;

                dx = mXs[indexOfClosest + 1] - mXs[indexOfClosest];
                dy = mYs[indexOfClosest + 1] - mYs[indexOfClosest];
                norm = sqrt(pow(dx, 2) + pow(dy, 2));

                dx = dx / norm;
                dy = dy / norm;

                newX = lastX + dx * interpointDistance;
                newY = lastY + dy * interpointDistance;

                lastX = newX;
                lastY = newY;

                resampledGesture.addPoint(newX, newY);
            }

            return resampledGesture;
        }

        /**
         * Normalizes the gesture by dividing it by the longest side of its bounding box and
         * centering it in zero.
         *
         * @return A normalized copy of the gesture.
         */
        public Gesture normalizeByBoxSide() {
            Gesture normalizedGesture = new Gesture();

            double maxX = -1;
            double maxY = -1;
            double minX = 10000;
            double minY = 10000;

            for (int i = 0; i < mCurrentLength; i++) {
                maxX = max(mXs[i], maxX);
                maxY = max(mYs[i], maxY);
                minX = min(mXs[i], minX);
                minY = min(mYs[i], minY);
            }

            double width = maxX - minX;
            double height = maxY - minY;
            double longestSide = max(width, height);

            double centroidX = (width / 2 + minX) / longestSide;
            double centroidY = (height / 2 + minY) / longestSide;

            double x;
            double y;
            for (int i = 0; i < mCurrentLength; i++) {
                x = mXs[i] / longestSide - centroidX;
                y = mYs[i] / longestSide - centroidY;
                normalizedGesture.addPoint(x, y);
            }

            return normalizedGesture;
        }

        /**
         * Generates an ideal gesture for the given word. An ideal gesture is one that passes
         * through the center of every character in the target word.
         *
         * @param word            The target word whose gesture we want.
         * @param keysByCharacter An array that associates characters to the corresponding keyboard
         *                        key.
         * @return A new gesture that goes through the center of every key in the word.
         */
        static Gesture generateIdealGesture(
                char[] word, SparseArray<Keyboard.Key> keysByCharacter) {
            Gesture idealGesture = new Gesture();

            char previousLetter = '\0';

            // Add points for each key
            for (char c : word) {
                c = Character.toLowerCase(c);
                if (previousLetter == c) continue; // Avoid duplicate letters

                Keyboard.Key key = keysByCharacter.get(c);

                if (key == null) {
                    // Try finding the base character instead, e.g., the "e" key instead of "é"
                    char baseCharacter = Dictionary.toLowerCase(c);
                    key = keysByCharacter.get(baseCharacter);
                    if (key == null) {
                        Logger.w(TAG, "Key %s not found on keyboard!", c);
                        continue;
                    }
                }

                previousLetter = c;
                idealGesture.addPoint(key.centerX, key.centerY);
            }

            return idealGesture;
        }
    }

    public String getWorkspaceToString(Gesture gesture) {
        final double[] xCoords = Arrays.copyOfRange(gesture.mXs, 0, gesture.mCurrentLength);
        final double[] yCoords = Arrays.copyOfRange(gesture.mYs, 0, gesture.mCurrentLength);

        return Arrays.toString(xCoords) + "," + Arrays.toString(yCoords);
    }
}
