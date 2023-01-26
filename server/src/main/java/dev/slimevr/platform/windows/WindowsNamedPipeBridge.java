package dev.slimevr.platform.windows;

import com.google.protobuf.CodedOutputStream;
// import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import dev.slimevr.Main;
import dev.slimevr.VRServer;
import dev.slimevr.bridge.BridgeThread;
import dev.slimevr.bridge.PipeState;
import dev.slimevr.bridge.ProtobufMessages.ProtobufMessage;
import dev.slimevr.platform.SteamVRBridge;
import dev.slimevr.vr.trackers.*;
import io.eiren.util.ann.ThreadSafe;
import io.eiren.util.logging.LogManager;

import java.io.IOException;
import java.util.List;


public class WindowsNamedPipeBridge extends SteamVRBridge {

	protected final String pipeName;
	private final byte[] sendBuf = new byte[2048];
	private final byte[] recvBuf = new byte[2048];
	protected WindowsPipe pipe;
	protected WinBase.OVERLAPPED connect_event;
	protected WinBase.OVERLAPPED recv_event;
	protected WinBase.OVERLAPPED send_event;
	protected HANDLE queue_event;

	protected HANDLE[] wait_send;
	protected HANDLE[] wait_queue;

	protected static HANDLE createEvent(boolean manual) throws IOException {
		HANDLE result = Kernel32.INSTANCE.CreateEvent(null, manual, false, null);
		if (result == WinBase.INVALID_HANDLE_VALUE) {
			throw new IOException("Event creation error: " + Kernel32.INSTANCE.GetLastError());
		}
		return result;
	}

	protected static WinBase.OVERLAPPED createOverlapped() throws IOException {
		WinBase.OVERLAPPED res = new WinBase.OVERLAPPED();
		res.hEvent = createEvent(true);
		res.Offset = 0;
		res.OffsetHigh = 0;
		return res;
	}

	public WindowsNamedPipeBridge(
		VRServer server,
		HMDTracker hmd,
		String bridgeSettingsKey,
		String bridgeName,
		String pipeName,
		List<? extends ShareableTracker> shareableTrackers
	) {
		super(server, hmd, "Named pipe thread", bridgeName, bridgeSettingsKey, shareableTrackers);
		this.pipeName = pipeName;
	}

	@Override
	@BridgeThread
	public void run() {
		try {
			connect_event = createOverlapped();
			recv_event = createOverlapped();
			send_event = createOverlapped();
			queue_event = createEvent(false);

			wait_send = new HANDLE[] { recv_event.hEvent, send_event.hEvent };
			wait_queue = new HANDLE[] { recv_event.hEvent, queue_event };
			createPipe();

			boolean send_pending = false;

			while (true) {
				int object = Kernel32.INSTANCE
					.WaitForMultipleObjects(
						2,
						send_pending ? wait_send : wait_queue,
						false,
						WinBase.INFINITE
					);
				switch (object - WinBase.WAIT_OBJECT_0) {
					case 0: // recv
						handleRecv();
						break;
					case 1:
						if (send_pending) { // send

						}
						// queue
						break;
					default:
				}
				boolean pipesUpdated = false;
				if (pipe.state == PipeState.CREATED) {
					tryOpeningPipe(pipe);
				}
				if (pipe.state == PipeState.OPEN) {
					pipesUpdated = updatePipe();
					updateMessageQueue();
				}
				if (pipe.state == PipeState.ERROR) {
					resetPipe();
				}
				if (!pipesUpdated) {
					Thread.yield();
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	@ThreadSafe
	protected void sendMessage(ProtobufMessage message) {
		super.sendMessage(message);
		if (queue_event != WinBase.INVALID_HANDLE_VALUE) {
			// since we're going to block on WaitForMultipleObjects, we need to
			// notify in a way that can be monitored there.
			Kernel32.INSTANCE.SetEvent(queue_event);
		}
	}

	@Override
	@BridgeThread
	protected boolean sendMessageReal(ProtobufMessage message) {
		if (pipe.state == PipeState.OPEN) {
			try {
				int size = message.getSerializedSize();
				CodedOutputStream os = CodedOutputStream.newInstance(sendBuf, 4, size);
				message.writeTo(os);
				size += 4;
				sendBuf[0] = (byte) (size & 0xFF);
				sendBuf[1] = (byte) ((size >> 8) & 0xFF);
				sendBuf[2] = (byte) ((size >> 16) & 0xFF);
				sendBuf[3] = (byte) ((size >> 24) & 0xFF);
				if (Kernel32.INSTANCE.WriteFile(pipe.pipeHandle, sendBuf, size, null, null)) {
					return true;
				}
				pipe.state = PipeState.ERROR;
				LogManager
					.severe("[" + bridgeName + "] Pipe error: " + Kernel32.INSTANCE.GetLastError());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return false;
	}

	private void startRecv() {
		Kernel32.INSTANCE.ReadFile(pipe.pipeHandle, recvBuf, recvBuf.length, null, recv_event);

		int err = Kernel32.INSTANCE.GetLastError();
		// TODO: is there a different return value for having immediately
		// finished?
		if (err != WinError.ERROR_IO_PENDING) {
			return;
		}

		pipe.state = PipeState.ERROR;
		LogManager
			.severe("[" + bridgeName + "] Pipe error: " + Kernel32.INSTANCE.GetLastError());
	}

	private void handleRecv() throws IOException {
		IntByReference bytes_recieved = new IntByReference(0);

		if (
			!Kernel32.INSTANCE
				.GetOverlappedResult(pipe.pipeHandle, recv_event, bytes_recieved, false)
				|| bytes_recieved.getValue() == 0
		) {
			pipe.state = PipeState.ERROR;
			return;
		}

		messageReceived(ProtobufMessage.parser().parseFrom(recvBuf, 0, bytes_recieved.getValue()));
		// we were successful, start the next read.
		startRecv();
	}

	private boolean updatePipe() throws IOException {
		if (pipe.state == PipeState.OPEN) {
			boolean readAnything = false;
			IntByReference bytesAvailable = new IntByReference(0);
			while (
				Kernel32.INSTANCE
					.PeekNamedPipe(pipe.pipeHandle, sendBuf, 4, null, bytesAvailable, null)
			) {
				if (bytesAvailable.getValue() >= 4) { // Got size
					int messageLength = (sendBuf[3] << 24)
						| (sendBuf[2] << 16)
						| (sendBuf[1] << 8)
						| sendBuf[0];
					if (messageLength > 1024) { // Overflow
						LogManager
							.severe(
								"["
									+ bridgeName
									+ "] Pipe overflow. Message length: "
									+ messageLength
							);
						pipe.state = PipeState.ERROR;
						return readAnything;
					}
					if (bytesAvailable.getValue() >= messageLength) {
						if (
							Kernel32.INSTANCE
								.ReadFile(
									pipe.pipeHandle,
									sendBuf,
									messageLength,
									bytesAvailable,
									null
								)
						) {
							ProtobufMessage message = ProtobufMessage
								.parser()
								.parseFrom(sendBuf, 4, messageLength - 4);
							messageReceived(message);
							readAnything = true;
						} else {
							pipe.state = PipeState.ERROR;
							LogManager
								.severe(
									"["
										+ bridgeName
										+ "] Pipe error: "
										+ Kernel32.INSTANCE.GetLastError()
								);
							return readAnything;
						}
					} else {
						return readAnything; // Wait for more data
					}
				} else {
					return readAnything; // Wait for more data
				}
			}
			pipe.state = PipeState.ERROR;
			LogManager
				.severe("[" + bridgeName + "] Pipe error: " + Kernel32.INSTANCE.GetLastError());
		}
		return false;
	}

	private void resetPipe() {
		WindowsPipe.safeDisconnect(pipe);
		pipe.state = PipeState.CREATED;
		Main.getVrServer().queueTask(this::disconnected);
	}

	private void createPipe() throws IOException {
		try {
			pipe = new WindowsPipe(
				Kernel32.INSTANCE
					.CreateNamedPipe(
						pipeName,
						WinBase.PIPE_ACCESS_DUPLEX | WinNT.FILE_FLAG_OVERLAPPED, // dwOpenMode
						WinBase.PIPE_TYPE_MESSAGE
							| WinBase.PIPE_READMODE_MESSAGE
							| WinBase.PIPE_WAIT, // dwPipeMode
						1, // nMaxInstances,
						1024 * 16, // nOutBufferSize,
						1024 * 16, // nInBufferSize,
						0, // nDefaultTimeOut,
						null
					),
				pipeName
			); // lpSecurityAttributes
			LogManager.info("[" + bridgeName + "] Pipe " + pipe.name + " created");
			if (WinBase.INVALID_HANDLE_VALUE.equals(pipe.pipeHandle))
				throw new IOException(
					"Can't open " + pipeName + " pipe: " + Kernel32.INSTANCE.GetLastError()
				);
			LogManager.info("[" + bridgeName + "] Pipes are created");
		} catch (IOException e) {
			WindowsPipe.safeDisconnect(pipe);
			throw e;
		}
	}

	private boolean tryOpeningPipe(WindowsPipe pipe) {
		// Overlapped ConnectNamedPipe should return zero. Checking this may be
		// overkill.
		if (Kernel32.INSTANCE.ConnectNamedPipe(pipe.pipeHandle, connect_event)) {
			LogManager
				.info(
					"["
						+ bridgeName
						+ "] Immediate error connecting to pipe "
						+ pipe.name
						+ ": "
						+ Kernel32.INSTANCE.GetLastError()
				);
		}

		IntByReference bytesRead = new IntByReference();

		switch (Kernel32.INSTANCE.GetLastError()) {
			case WinError.ERROR_IO_PENDING:
				// TODO: named pipes can support multiple clients. We could make
				// connect non-blocking,
				// and handle multiple clients (i.e. feeder and driver) using
				// the same thread.
				boolean result = Kernel32.INSTANCE
					.GetOverlappedResult(pipe.pipeHandle, connect_event, bytesRead, true);
				if (!result) {
					LogManager
						.info(
							"["
								+ bridgeName
								+ "] Error connecting to pipe "
								+ pipe.name
								+ ": "
								+ Kernel32.INSTANCE.GetLastError()
						);
				} else {
					pipe.state = PipeState.OPEN;
					startRecv();
				}
				return result;
			case WinError.ERROR_PIPE_CONNECTED:
				// example code at
				// <https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-server-using-overlapped-i-o>
				// sets an event in this case: I don't think we need to do that,
				// since we're only calling GetOverlappedResult if
				// the operation is pending.
				pipe.state = PipeState.OPEN;
				return true;
			default:
				LogManager
					.info(
						"["
							+ bridgeName
							+ "] Error connecting to pipe "
							+ pipe.name
							+ ": "
							+ Kernel32.INSTANCE.GetLastError()
					);
				pipe.state = PipeState.ERROR;
				return false;
		}
	}

	@Override
	public boolean isConnected() {
		return pipe != null && pipe.state == PipeState.OPEN;
	}
}
