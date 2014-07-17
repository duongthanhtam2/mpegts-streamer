package org.taktik.mpegts.sources;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taktik.ioutils.NIOUtils;
import org.taktik.mpegts.Constants;
import org.taktik.mpegts.MTSPacket;

public class SeekableByteChannelMTSSource  extends AbstractMTSSource implements ResettableMTSSource {
	static final Logger log = LoggerFactory.getLogger("source");

	private static final int BUFFER_SIZE = Constants.MPEGTS_PACKET_SIZE * 1000;

	private SeekableByteChannel byteChannel;
	private ByteBuffer buffer;

	private SeekableByteChannelMTSSource(SeekableByteChannel byteChannel) throws IOException {
		this.byteChannel = byteChannel;
		fillBuffer();
	}

	private void fillBuffer() throws IOException {
		buffer = ByteBuffer.allocate(BUFFER_SIZE);
		NIOUtils.read(byteChannel, buffer);
		buffer.flip();
	}

	private boolean lastBuffer() {
		return buffer.capacity() > buffer.limit();
	}

	@Override
	protected MTSPacket nextPacketInternal() throws IOException {
		ByteBuffer packetBuffer = null;
		while (true) {
			boolean foundFirstMarker = false;
			int skipped = 0;
			while (!foundFirstMarker) {
				if (!buffer.hasRemaining()) {
					if (lastBuffer()) {
						return null;
					}
					buffer = ByteBuffer.allocate(BUFFER_SIZE);
					if (NIOUtils.read(byteChannel, buffer) <= 0) {
						return null;
					}
					buffer.flip();
				}
				if ((buffer.get(buffer.position()) & 0xff) == Constants.TS_MARKER) {
					foundFirstMarker = true;
				} else {
					buffer.position(buffer.position() + 1);
					skipped++;
				}
			}
			if (skipped > 0) {
				log.info("Skipped {} bytes looking for TS marker", skipped);
			}
			if (buffer.remaining() >= Constants.MPEGTS_PACKET_SIZE) {
				if ((buffer.remaining() == Constants.MPEGTS_PACKET_SIZE) ||
						(buffer.get(buffer.position() + Constants.MPEGTS_PACKET_SIZE) & 0xff) == Constants.TS_MARKER) {
					packetBuffer = buffer.slice();
					packetBuffer.limit(Constants.MPEGTS_PACKET_SIZE);
					buffer.position(buffer.position() + Constants.MPEGTS_PACKET_SIZE);
				} else {
					log.info("no second marker found");
					buffer.position(buffer.position() + 1);
				}
			} else if (!lastBuffer()) {
				log.info("NEW BUFFER");

				ByteBuffer newBuffer = ByteBuffer.allocate(BUFFER_SIZE);
				newBuffer.put(buffer);
				buffer = newBuffer;
				if (NIOUtils.read(byteChannel, buffer) <= 0) {
					return null;
				}
				buffer.flip();
				if (buffer.remaining() >= Constants.MPEGTS_PACKET_SIZE) {
					if ((buffer.remaining() == Constants.MPEGTS_PACKET_SIZE) ||
							(buffer.get(buffer.position() + Constants.MPEGTS_PACKET_SIZE) & 0xff) == Constants.TS_MARKER) {
						packetBuffer = buffer.slice();
						packetBuffer.limit(Constants.MPEGTS_PACKET_SIZE);
						buffer.position(buffer.position() + Constants.MPEGTS_PACKET_SIZE);
					} else {
						log.info("no second marker found");
						buffer.position(buffer.position() + 1);
					}
				} else {
					return null;
				}
			} else {
				return null;
			}

			if (packetBuffer != null) {
				// Parse the packet
				return new MTSPacket(packetBuffer);
			}
		}
	}

	@Override
	protected void closeInternal() throws Exception {
		byteChannel.close();
	}

	public static SeekableByteChannelMTSSourceBuilder builder() {
		return new SeekableByteChannelMTSSourceBuilder();
	}

	@Override
	public void reset() throws IOException {
		byteChannel.position(0);
		fillBuffer();
	}

	public static class SeekableByteChannelMTSSourceBuilder {
		private SeekableByteChannel byteChannel;

		private SeekableByteChannelMTSSourceBuilder(){}

		public SeekableByteChannelMTSSourceBuilder setByteChannel(SeekableByteChannel byteChannel) {
			this.byteChannel = byteChannel;
			return this;
		}

		public SeekableByteChannelMTSSource build() throws IOException {
			Preconditions.checkNotNull(byteChannel, "byteChannel cannot be null");
			return new SeekableByteChannelMTSSource(byteChannel);
		}
	}
}
