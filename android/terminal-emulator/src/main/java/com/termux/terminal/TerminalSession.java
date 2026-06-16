package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sesión de terminal AGNÓSTICA AL TRANSPORTE (Remoteclaude).
 *
 * Original de Termux (GPLv3), modificada: se quitó el spawner de proceso local
 * (JNI/PTY) para poder alimentar el emulador desde cualquier transporte (eco,
 * SSH, …). La subclase implementa:
 *   - {@link #writeToTransport}: lo que el usuario tipea → mandarlo al host.
 *   - {@link #onEmulatorInitialized}: arrancar el transporte (abrir SSH, etc.).
 *   - {@link #closeTransport}: cerrar el transporte.
 * y llama a {@link #onTransportInput} con los bytes recibidos del host.
 *
 * Toda la emulación y los callbacks ocurren en el main thread.
 */
public abstract class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Bytes del host → terminal (lo escribe el transporte, lo lee el main thread). */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** Input del usuario → host (lo escribe el main thread, lo drena el hilo escritor). */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    /** Identificación de la sesión para la app (no la usa el emulador). */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    private volatile boolean mRunning = false;

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Inicializa el emulador (primer llamado) o lo redimensiona. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            onSizeChanged(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        mRunning = true;

        // Hilo que drena el input del usuario y lo manda al transporte.
        new Thread("TermSessionWriter[" + mHandle + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                while (true) {
                    int n = mTerminalToProcessIOQueue.read(buffer, true);
                    if (n == -1) return;
                    try {
                        writeToTransport(buffer, 0, n);
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        }.start();

        onEmulatorInitialized(columns, rows, cellWidthPixels, cellHeightPixels);
    }

    /** La subclase arranca el transporte acá (p.ej. abre la conexión SSH). */
    protected abstract void onEmulatorInitialized(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Envía al host lo que tipea el usuario. Corre en el hilo escritor; puede bloquear. */
    protected abstract void writeToTransport(byte[] data, int offset, int count) throws Exception;

    /** La subclase cierra el transporte acá. */
    protected abstract void closeTransport();

    /** Hook opcional: avisar al transporte de un cambio de tamaño (SSH window-change). */
    protected void onSizeChanged(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
    }

    /** La subclase llama esto con los bytes recibidos del host → van al emulador. */
    public void onTransportInput(byte[] data, int offset, int count) {
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** La subclase avisa que el transporte se cerró (con un mensaje opcional para mostrar). */
    public void onTransportClosed(final String message) {
        mMainThreadHandler.post(() -> {
            if (mEmulator != null && message != null) {
                byte[] b = message.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(b, b.length);
                notifyScreenUpdate();
            }
            mRunning = false;
            if (mClient != null) mClient.onSessionFinished(this);
        });
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Escribe el code point Unicode codificado en UTF-8 al host. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* máx 21 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    /** Termina la sesión cerrando el transporte. */
    public void finishIfRunning() {
        if (mRunning) {
            mRunning = false;
            mTerminalToProcessIOQueue.close();
            mProcessToTerminalIOQueue.close();
            try {
                closeTransport();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }
        }
    }
}
