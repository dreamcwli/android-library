package org.dreamcwli.android.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

public class BluetoothService {
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_LISTENING = 2;
    public static final int STATE_CONNECTED = 3;

    private static final String TAG = "BluetoothService";

    private final BluetoothAdapter mAdapter;
    private final String mName;
    private final UUID mUuid;

    private int mState;
    private ClientThread mClientThread;
    private ServerThread mServerThread;
    private ConnectionThread mConnectionThread;

    public BluetoothService(BluetoothAdapter adapter, String name, UUID uuid) {
        mAdapter = adapter;
        mName = name;
        mUuid = uuid;
        mState = STATE_IDLE;
    }

    public synchronized void connect(BluetoothDevice device, boolean secure) {
        halt();
        mClientThread = new ClientThread(device, secure);
        mClientThread.start();
        setState(STATE_CONNECTING);
    }

    public synchronized void listen(boolean secure) {
        halt();
        mServerThread = new ServerThread(secure);
        mServerThread.start();
        setState(STATE_LISTENING);
    }

    public synchronized void stop() {
        halt();
        setState(STATE_IDLE);
    }

    public synchronized int getState() {
        return mState;
    }

    protected synchronized void setState(int state) {
        mState = state;
    }

    protected synchronized void onConnected(BluetoothSocket socket) {
        halt();
        mConnectionThread = new ConnectionThread(socket);
        mConnectionThread.start();
        setState(STATE_CONNECTED);
    }

    protected synchronized void onClientFailed() {
        if (mState == STATE_CONNECTING) {
            stop();
        }
    }

    protected synchronized void onServerFailed() {
        if (mState == STATE_LISTENING) {
            stop();
        }
    }

    protected synchronized void onConnectionFailed() {
        if (mState == STATE_CONNECTED) {
            stop();
        }
    }

    private synchronized void halt() {
        if (mClientThread != null) {
            mClientThread.halt();
            mClientThread = null;
        }
        if (mServerThread != null) {
            mServerThread.halt();
            mServerThread = null;
        }
        if (mConnectionThread != null) {
            mConnectionThread.halt();
            mConnectionThread = null;
        }
    }

    private class ClientThread extends Thread {
        private BluetoothSocket mSocket;

        public ClientThread(BluetoothDevice device, boolean secure) {
            try {
                if (secure) {
                    mSocket = device.createRfcommSocketToServiceRecord(mUuid);
                } else {
                    mSocket = device.createInsecureRfcommSocketToServiceRecord(mUuid);
                }
            } catch (IOException e) {
                Log.e(TAG, "failed to start connecting to remote device", e);
            }
        }

        public void halt() {
            if (mSocket == null) {
                return;
            }

            try {
                mSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "failed to stop connecting to remote device", e);
            }
        }

        @Override
        public void run() {
            if (mSocket == null) {
                return;
            }

            try {
                mSocket.connect();
            } catch (IOException e) {
                Log.e(TAG, "failed to connect to remote device", e);
                try {
                    mSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "failed to stop connecting to remote device", e2);
                }
                onClientFailed();
                return;
            }
            onConnected(mSocket);
        }
    }

    private class ServerThread extends Thread {
        private BluetoothServerSocket mServerSocket;

        public ServerThread(boolean secure) {
            try {
                if (secure) {
                    mServerSocket = mAdapter.listenUsingRfcommWithServiceRecord(mName, mUuid);
                } else {
                    mServerSocket =
                            mAdapter.listenUsingInsecureRfcommWithServiceRecord(mName, mUuid);
                }
            } catch (IOException e) {
                Log.e(TAG, "failed to start listening for connections", e);
            }
        }

        public void halt() {
            if (mServerSocket == null) {
                return;
            }

            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "failed to stop listening for connections", e);
            }
        }

        @Override
        public void run() {
            if (mServerSocket == null) {
                return;
            }

            while (true) {
                BluetoothSocket socket;
                try {
                    socket = mServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "failed to accept connection", e);
                    try {
                        mServerSocket.close();
                    } catch (IOException e2) {
                        Log.e(TAG, "failed to stop listening for connections", e2);
                    }
                    onServerFailed();
                    break;
                }
                onConnected(socket);
            }
        }
    }

    private class ConnectionThread extends Thread {
        private static final byte MAJOR_VERSION = 0;
        private static final byte MINOR_VERSION = 1;
        private static final int VERSION_SIZE = 2;
        private static final int LENGTH_SIZE = Integer.SIZE / Byte.SIZE;
        private static final int HEADER_SIZE = VERSION_SIZE + LENGTH_SIZE;
        private static final int BUFFER_SIZE = 4096;

        private BluetoothSocket mSocket;
        private InputStream mInputStream;
        private OutputStream mOutputStream;

        public ConnectionThread(BluetoothSocket socket) {
            mSocket = socket;
            try {
                mInputStream = socket.getInputStream();
                mOutputStream = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "failed to get input/output stream", e);
            }
        }

        public void send(byte[] data) {
            byte[] header = new byte[HEADER_SIZE];
            header[0] = MAJOR_VERSION;
            header[1] = MINOR_VERSION;
            byte[] buffer = ByteBuffer.allocate(LENGTH_SIZE).putInt(data.length).array();
            System.arraycopy(buffer, 0, header, VERSION_SIZE, LENGTH_SIZE);
            try {
                mOutputStream.write(header);
                mOutputStream.write(data);
            } catch (IOException e) {
                Log.e(TAG, "failed to send data", e);
                try {
                    mSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "failed to close connection", e2);
                }
                onConnectionFailed();
            }
        }

        public void halt() {
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "failed to close connection", e);
            }
        }

        @Override
        public void run() {
            if (mInputStream == null || mOutputStream == null) {
                return;
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
            boolean ready = false;
            int required = HEADER_SIZE;
            while (true) {
                try {
                    int offset = 0;
                    int available = mInputStream.read(buffer, 0, BUFFER_SIZE);
                    while (true) {
                        if (available < required) {
                            dataStream.write(buffer, offset, available);
                            required -= available;
                            break;
                        } else {
                            dataStream.write(buffer, offset, required);
                            offset += required;
                            available -= required;
                            if (ready) {
                                byte[] data = dataStream.toByteArray();
                                // notify data received
                                ready = true;
                                required = HEADER_SIZE;
                            } else {
                                byte[] header = dataStream.toByteArray();
                                // verify header
                                ready = false;
                                required =
                                        ByteBuffer.wrap(header, VERSION_SIZE, LENGTH_SIZE).getInt();
                            }
                            dataStream.reset();
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "failed to read data", e);
                    try {
                        mSocket.close();
                    } catch (IOException e2) {
                        Log.e(TAG, "failed to close connection", e2);
                    }
                    onConnectionFailed();
                    break;
                }
            }
        }
    }
}
