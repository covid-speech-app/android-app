package com.project.coswara.util;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Reference: https://gist.github.com/kmark/d8b1b01fb0d2febf5770
 */

public class RecordWaveTask extends AsyncTask<File, Void, Object[]> {

    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int SAMPLE_RATE = 44100; // Hz
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;

    private static final int BUFFER_SIZE = 2 * AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);

    private Context ctx;

    private final long TIME_LIMIT_SECS = 60L; //60 seconds upper limit

    public RecordWaveTask(Context ctx) {
        setContext(ctx);
    }

    private void setContext(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Opens up the given file, writes the header, and keeps filling it with raw PCM bytes from
     * AudioRecord until it reaches 5MB or is stopped by the user. It then goes back and updates
     * the WAV header to include the proper final chunk sizes.
     *
     * @param files Index 0 should be the file to write to
     * @return Either an Exception (error) or two longs, the filesize, elapsed time in ms (success)
     */
    @Override
    protected Object[] doInBackground(File... files) {
        AudioRecord audioRecord = null;
        FileOutputStream wavOut = null;
        long startTime;
        long endTime = 0;
        long runningTime = 0; //in seconds

        try {
            // Open our two resources
            audioRecord = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_MASK, ENCODING, BUFFER_SIZE);
            wavOut = new FileOutputStream(files[0]);

            // Write out the wav file header
            writeWavHeader(wavOut, CHANNEL_MASK, SAMPLE_RATE, ENCODING);

            // Avoiding loop allocations
            byte[] buffer = new byte[BUFFER_SIZE];
            boolean run = true;
            int read;
            long total = 0;

            startTime = SystemClock.elapsedRealtime();
            audioRecord.startRecording();
            while (run && !isCancelled() && runningTime < TIME_LIMIT_SECS) { //ensure less than 30s
                read = audioRecord.read(buffer, 0, buffer.length);

                runningTime = (SystemClock.elapsedRealtime() - startTime) / 1000L;

                // Ensure size < 5MB
                if (total + read > 5242880L) {
                    // Write as many bytes as we can before hitting the max size
                    for (int i = 0; i < read && total <= 5242880L; i++, total++) {
                        wavOut.write(buffer[i]);
                    }
                    run = false;
                } else {
                    // Write out the entire read buffer
                    wavOut.write(buffer, 0, read);
                    total += read;
                }
            }
        } catch (IOException ex) {
            return new Object[]{ex};
        } finally {
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                        endTime = SystemClock.elapsedRealtime();
                    }
                } catch (IllegalStateException ex) {
                    //
                }
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release();
                }
            }
            if (wavOut != null) {
                try {
                    wavOut.close();
                } catch (IOException ex) {
                    //
                }
            }
        }

        try {
            // This is not put in the try/catch/finally above since it needs to run
            // after we close the FileOutputStream
            updateWavHeader(files[0]);
        } catch (IOException ex) {
            return new Object[] { ex };
        }

        return new Object[] { files[0].length(), endTime - startTime };
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out         The stream to write the header to
     * @param channelMask An AudioFormat.CHANNEL_* mask
     * @param sampleRate  The sample rate in hertz
     * @param encoding    An AudioFormat.ENCODING_PCM_* value
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, int channelMask, int sampleRate, int encoding) throws IOException {
        short channels;
        switch (channelMask) {
            case AudioFormat.CHANNEL_IN_MONO:
                channels = 1;
                break;
            case AudioFormat.CHANNEL_IN_STEREO:
                channels = 2;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable channel mask");
        }

        short bitDepth;
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                bitDepth = 8;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bitDepth = 16;
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                bitDepth = 32;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable encoding");
        }

        writeWavHeader(out, channels, sampleRate, bitDepth);
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out        The stream to write the header to
     * @param channels   The number of channels
     * @param sampleRate The sample rate in hertz
     * @param bitDepth   The bit depth
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, short channels, int sampleRate, short bitDepth) throws IOException {
        // Convert the multi-byte integers to raw bytes in little endian format as required by the spec
        byte[] littleBytes = ByteBuffer
                .allocate(14)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(channels)
                .putInt(sampleRate)
                .putInt(sampleRate * channels * (bitDepth / 8))
                .putShort((short) (channels * (bitDepth / 8)))
                .putShort(bitDepth)
                .array();

        // Not necessarily the best, but it's very easy to visualize this way
        out.write(new byte[]{
                // RIFF header
                'R', 'I', 'F', 'F', // ChunkID
                0, 0, 0, 0, // ChunkSize (must be updated later)
                'W', 'A', 'V', 'E', // Format
                // fmt subchunk
                'f', 'm', 't', ' ', // Subchunk1ID
                16, 0, 0, 0, // Subchunk1Size
                1, 0, // AudioFormat
                littleBytes[0], littleBytes[1], // NumChannels
                littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // ByteRate
                littleBytes[10], littleBytes[11], // BlockAlign
                littleBytes[12], littleBytes[13], // BitsPerSample
                // data subchunk
                'd', 'a', 't', 'a', // Subchunk2ID
                0, 0, 0, 0, // Subchunk2Size (must be updated later)
        });
    }

    /**
     * Updates the given wav file's header to include the final chunk sizes
     *
     * @param wav The wav file to update
     * @throws IOException
     */
    private static void updateWavHeader(File wav) throws IOException {
        byte[] sizes = ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                // There are probably a bunch of different/better ways to calculate
                // these two given your circumstances. Cast should be safe since if the WAV is
                // > 4 GB we've already made a terrible mistake.
                .putInt((int) (wav.length() - 8)) // ChunkSize
                .putInt((int) (wav.length() - 44)) // Subchunk2Size
                .array();

        RandomAccessFile accessWave = null;
        //noinspection CaughtExceptionImmediatelyRethrown
        try {
            accessWave = new RandomAccessFile(wav, "rw");
            // ChunkSize
            accessWave.seek(4);
            accessWave.write(sizes, 0, 4);

            // Subchunk2Size
            accessWave.seek(40);
            accessWave.write(sizes, 4, 4);
        } catch (IOException ex) {
            // Rethrow but we still close accessWave in our finally
            throw ex;
        } finally {
            if (accessWave != null) {
                try {
                    accessWave.close();
                } catch (IOException ex) {
                    //
                }
            }
        }
    }

    @Override
    protected void onCancelled(Object[] results) {
        // Handling cancellations and successful runs in the same way
        onPostExecute(results);
    }

    @Override
    protected void onPostExecute(Object[] results) {
        Throwable throwable = null;
        if (results[0] instanceof Throwable) {
            // Error
            throwable = (Throwable) results[0];
            Log.e(RecordWaveTask.class.getSimpleName(), throwable.getMessage(), throwable);
        }

        // If we're attached to an activity
        if (ctx != null) {
            if (throwable == null) {
                // Display final recording stats
                double size = (long) results[0] / 1000000.00;
                long time = (long) results[1] / 1000;

                if(time >= TIME_LIMIT_SECS){
                    Toast.makeText(ctx, String.format(Locale.getDefault(),
                            "Recording stopped as upper time limit of %d seconds reached", TIME_LIMIT_SECS),
                            Toast.LENGTH_SHORT).show();
                }

                System.out.println("record: " +
                        String.format(Locale.getDefault(), "%.2f MB / %d seconds", size, time));
            } else {
                // Error
                Toast.makeText(ctx, throwable.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
