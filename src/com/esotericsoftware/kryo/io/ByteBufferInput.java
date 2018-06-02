/* Copyright (c) 2008-2017, Nathan Sweet
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryo.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.util.Util;

/** An {@link Input} that uses a ByteBuffer rather than a byte[].
 * <p>
 * Note that the byte[] {@link #getBuffer() buffer} is not used. Code taking an Input and expecting the byte[] to be used may not
 * work correctly.
 * @author Roman Levenstein <romixlev@gmail.com> */
public class ByteBufferInput extends Input {
	static private final ByteOrder nativeOrder = ByteOrder.nativeOrder();

	protected ByteBuffer byteBuffer;
	private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN; // Compatible with Input by default.

	/** Creates an uninitialized Input, {@link #setBuffer(ByteBuffer)} must be called before the Input is used. */
	public ByteBufferInput () {
	}

	/** Creates a new Input for reading from a direct {@link ByteBuffer}.
	 * @param bufferSize The size of the buffer. An exception is thrown if more bytes than this are read and
	 *           {@link #fill(ByteBuffer, int, int)} does not supply more bytes. */
	public ByteBufferInput (int bufferSize) {
		this.capacity = bufferSize;
		byteBuffer = ByteBuffer.allocateDirect(bufferSize);
		byteBuffer.order(byteOrder);
	}

	/** Creates a new Input for reading from a {@link ByteBuffer} which is filled with the specified bytes. */
	public ByteBufferInput (byte[] bytes) {
		setBuffer(bytes);
	}

	/** Creates a new Input for reading from a ByteBuffer. */
	public ByteBufferInput (ByteBuffer buffer) {
		setBuffer(buffer);
	}

	/** @see Input#Input(InputStream) */
	public ByteBufferInput (InputStream inputStream) {
		this(4096);
		if (inputStream == null) throw new IllegalArgumentException("inputStream cannot be null.");
		this.inputStream = inputStream;
	}

	/** @see Input#Input(InputStream, int) */
	public ByteBufferInput (InputStream inputStream, int bufferSize) {
		this(bufferSize);
		if (inputStream == null) throw new IllegalArgumentException("inputStream cannot be null.");
		this.inputStream = inputStream;
	}

	public ByteOrder order () {
		return byteOrder;
	}

	/** Sets the byte order. Default is big endian. */
	public void order (ByteOrder byteOrder) {
		this.byteOrder = byteOrder;
	}

	/** Allocates a new direct ByteBuffer with the specified bytes and sets it as the new buffer.
	 * @see #setBuffer(ByteBuffer) */
	public void setBuffer (byte[] bytes) {
		ByteBuffer directBuffer = ByteBuffer.allocateDirect(bytes.length);
		directBuffer.put(bytes);
		directBuffer.position(0);
		directBuffer.limit(bytes.length);
		directBuffer.order(byteOrder);
		setBuffer(directBuffer);
	}

	/** Sets a new buffer to read from. The bytes are not copied, the old buffer is discarded and the new buffer used in its place.
	 * The byte order, position, limit, and capacity are set to match the specified buffer. The total is reset. The
	 * {@link #setInputStream(InputStream) InputStream} is set to null. */
	public void setBuffer (ByteBuffer buffer) {
		if (buffer == null) throw new IllegalArgumentException("buffer cannot be null.");
		byteBuffer = buffer;
		position = buffer.position();
		limit = buffer.limit();
		capacity = buffer.capacity();
		byteOrder = buffer.order();
		total = 0;
		inputStream = null;
	}

	public ByteBuffer getByteBuffer () {
		return byteBuffer;
	}

	public InputStream getInputStream () {
		return inputStream;
	}

	public void setInputStream (InputStream inputStream) {
		this.inputStream = inputStream;
		limit = 0;
		rewind();
	}

	public void rewind () {
		super.rewind();
		byteBuffer.position(0);
	}

	/** Fills the buffer with more bytes. The default implementation reads from the {@link #getInputStream() InputStream}, if set.
	 * Can be overridden to fill the bytes from another source. */
	protected int fill (ByteBuffer buffer, int offset, int count) throws KryoException {
		if (inputStream == null) return -1;
		try {
			byte[] tmp = new byte[count];
			int result = inputStream.read(tmp, 0, count);
			buffer.position(offset);
			if (result >= 0) {
				buffer.put(tmp, 0, result);
				buffer.position(offset);
			}
			return result;
		} catch (IOException ex) {
			throw new KryoException(ex);
		}
	}

	protected int require (int required) throws KryoException {
		int remaining = limit - position;
		if (remaining >= required) return remaining;
		if (required > capacity) throw new KryoException("Buffer too small: capacity: " + capacity + ", required: " + required);

		int count;
		// Try to fill the buffer.
		if (remaining > 0) {
			count = fill(byteBuffer, limit, capacity - limit);
			if (count == -1) throw new KryoException("Buffer underflow.");
			remaining += count;
			if (remaining >= required) {
				limit += count;
				return remaining;
			}
		}

		// Compact. Position after compaction can be non-zero.
		byteBuffer.position(position);
		byteBuffer.compact();
		total += position;
		position = 0;

		while (true) {
			count = fill(byteBuffer, remaining, capacity - remaining);
			if (count == -1) {
				if (remaining >= required) break;
				throw new KryoException("Buffer underflow.");
			}
			remaining += count;
			if (remaining >= required) break; // Enough has been read.
		}
		limit = remaining;
		byteBuffer.position(0);
		return remaining;
	}

	/** Fills the buffer with at least the number of bytes specified, if possible.
	 * @param optional Must be > 0.
	 * @return the number of bytes remaining, but not more than optional, or -1 if {@link #fill(ByteBuffer, int, int)} is unable to
	 *         provide more bytes. */
	private int optional (int optional) throws KryoException {
		int remaining = limit - position;
		if (remaining >= optional) return optional;
		optional = Math.min(optional, capacity);

		// Try to fill the buffer.
		int count = fill(byteBuffer, limit, capacity - limit);
		if (count == -1) return remaining == 0 ? -1 : Math.min(remaining, optional);
		remaining += count;
		if (remaining >= optional) {
			limit += count;
			return optional;
		}

		// Compact.
		byteBuffer.compact();
		total += position;
		position = 0;

		while (true) {
			count = fill(byteBuffer, remaining, capacity - remaining);
			if (count == -1) break;
			remaining += count;
			if (remaining >= optional) break; // Enough has been read.
		}
		limit = remaining;
		byteBuffer.position(position);
		return remaining == 0 ? -1 : Math.min(remaining, optional);
	}

	// InputStream

	public int read () throws KryoException {
		if (optional(1) <= 0) return -1;
		position++;
		return byteBuffer.get() & 0xFF;
	}

	public int read (byte[] bytes) throws KryoException {
		return read(bytes, 0, bytes.length);
	}

	public int read (byte[] bytes, int offset, int count) throws KryoException {
		if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");
		int startingCount = count;
		int copyCount = Math.min(limit - position, count);
		while (true) {
			byteBuffer.get(bytes, offset, copyCount);
			position += copyCount;
			count -= copyCount;
			if (count == 0) break;
			offset += copyCount;
			copyCount = optional(count);
			if (copyCount == -1) {
				// End of data.
				if (startingCount == count) return -1;
				break;
			}
			if (position == limit) break;
		}
		return startingCount - count;
	}

	public void setPosition (int position) {
		this.position = position;
		byteBuffer.position(position);
	}

	public void setLimit (int limit) {
		this.limit = limit;
		byteBuffer.limit(limit);
	}

	public void skip (int count) throws KryoException {
		super.skip(count);
		byteBuffer.position(position());
	}

	public long skip (long count) throws KryoException {
		long remaining = count;
		while (remaining > 0) {
			int skip = (int)Math.min(Util.maxArraySize, remaining);
			skip(skip);
			remaining -= skip;
		}
		return count;
	}

	public void close () throws KryoException {
		if (inputStream != null) {
			try {
				inputStream.close();
			} catch (IOException ignored) {
			}
		}
	}

	// byte

	public byte readByte () throws KryoException {
		require(1);
		position++;
		return byteBuffer.get();
	}

	public int readByteUnsigned () throws KryoException {
		require(1);
		position++;
		return byteBuffer.get() & 0xFF;
	}

	public byte[] readBytes (int length) throws KryoException {
		byte[] bytes = new byte[length];
		readBytes(bytes, 0, length);
		return bytes;
	}

	public void readBytes (byte[] bytes) throws KryoException {
		readBytes(bytes, 0, bytes.length);
	}

	public void readBytes (byte[] bytes, int offset, int count) throws KryoException {
		if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");
		int copyCount = Math.min(limit - position, count);
		while (true) {
			byteBuffer.get(bytes, offset, copyCount);
			position += copyCount;
			count -= copyCount;
			if (count == 0) break;
			offset += copyCount;
			copyCount = Math.min(count, capacity);
			require(copyCount);
		}
	}

	public int readInt () throws KryoException {
		require(4);
		position += 4;
		return byteBuffer.getInt();
	}

	public int readInt (boolean optimizePositive) throws KryoException {
		if (require(1) < 5) return readInt_slow(optimizePositive);
		int p = position + 1;
		int b = byteBuffer.get();
		int result = b & 0x7F;
		if ((b & 0x80) != 0) {
			p++;
			b = byteBuffer.get();
			result |= (b & 0x7F) << 7;
			if ((b & 0x80) != 0) {
				p++;
				b = byteBuffer.get();
				result |= (b & 0x7F) << 14;
				if ((b & 0x80) != 0) {
					p++;
					b = byteBuffer.get();
					result |= (b & 0x7F) << 21;
					if ((b & 0x80) != 0) {
						p++;
						b = byteBuffer.get();
						result |= (b & 0x7F) << 28;
					}
				}
			}
		}
		position = p;
		return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
	}

	private int readInt_slow (boolean optimizePositive) {
		// The buffer is guaranteed to have at least 1 byte.
		position++;
		int b = byteBuffer.get();
		int result = b & 0x7F;
		if ((b & 0x80) != 0) {
			require(1);
			position++;
			b = byteBuffer.get();
			result |= (b & 0x7F) << 7;
			if ((b & 0x80) != 0) {
				require(1);
				position++;
				b = byteBuffer.get();
				result |= (b & 0x7F) << 14;
				if ((b & 0x80) != 0) {
					require(1);
					position++;
					b = byteBuffer.get();
					result |= (b & 0x7F) << 21;
					if ((b & 0x80) != 0) {
						require(1);
						position++;
						b = byteBuffer.get();
						result |= (b & 0x7F) << 28;
					}
				}
			}
		}
		return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
	}

	public boolean canReadInt () throws KryoException {
		if (limit - position >= 5) return true;
		if (optional(5) <= 0) return false;
		int p = position;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		return true;
	}

	public boolean canReadLong () throws KryoException {
		if (limit - position >= 9) return true;
		if (optional(5) <= 0) return false;
		int p = position;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		if ((byteBuffer.get(p++) & 0x80) == 0) return true;
		if (p == limit) return false;
		return true;
	}

	public String readString () {
		int available = require(1);
		position++;
		int b = byteBuffer.get();
		if ((b & 0x80) == 0) return readAscii(); // ASCII.
		// Null, empty, or UTF8.
		int charCount = available >= 5 ? readUtf8Length(b) : readUtf8Length_slow(b);
		switch (charCount) {
		case 0:
			return null;
		case 1:
			return "";
		}
		charCount--;
		if (chars.length < charCount) chars = new char[charCount];
		readUtf8(charCount);
		return new String(chars, 0, charCount);
	}

	private int readUtf8Length (int b) {
		int result = b & 0x3F; // Mask all but first 6 bits.
		if ((b & 0x40) != 0) { // Bit 7 means another byte, bit 8 means UTF8.
			int p = position + 1;
			b = byteBuffer.get();
			result |= (b & 0x7F) << 6;
			if ((b & 0x80) != 0) {
				p++;
				b = byteBuffer.get();
				result |= (b & 0x7F) << 13;
				if ((b & 0x80) != 0) {
					p++;
					b = byteBuffer.get();
					result |= (b & 0x7F) << 20;
					if ((b & 0x80) != 0) {
						p++;
						b = byteBuffer.get();
						result |= (b & 0x7F) << 27;
					}
				}
			}
			position = p;
		}
		return result;
	}

	private int readUtf8Length_slow (int b) {
		int result = b & 0x3F; // Mask all but first 6 bits.
		if ((b & 0x40) != 0) { // Bit 7 means another byte, bit 8 means UTF8.
			require(1);
			position++;
			b = byteBuffer.get();
			result |= (b & 0x7F) << 6;
			if ((b & 0x80) != 0) {
				require(1);
				position++;
				b = byteBuffer.get();
				result |= (b & 0x7F) << 13;
				if ((b & 0x80) != 0) {
					require(1);
					position++;
					b = byteBuffer.get();
					result |= (b & 0x7F) << 20;
					if ((b & 0x80) != 0) {
						require(1);
						position++;
						b = byteBuffer.get();
						result |= (b & 0x7F) << 27;
					}
				}
			}
		}
		return result;
	}

	private void readUtf8 (int charCount) {
		char[] chars = this.chars;
		// Try to read 7 bit ASCII chars.
		int charIndex = 0;
		int count = Math.min(require(1), charCount);
		int p = this.position, b;
		while (charIndex < count) {
			p++;
			b = byteBuffer.get();
			if (b < 0) {
				p--;
				break;
			}
			chars[charIndex++] = (char)b;
		}
		this.position = p;
		// If buffer didn't hold all chars or any were not ASCII, use slow path for remainder.
		if (charIndex < charCount) {
			byteBuffer.position(p);
			readUtf8_slow(charCount, charIndex);
		}
	}

	private void readUtf8_slow (int charCount, int charIndex) {
		char[] chars = this.chars;
		while (charIndex < charCount) {
			if (position == limit) require(1);
			position++;
			int b = byteBuffer.get() & 0xFF;
			switch (b >> 4) {
			case 0:
			case 1:
			case 2:
			case 3:
			case 4:
			case 5:
			case 6:
			case 7:
				chars[charIndex] = (char)b;
				break;
			case 12:
			case 13:
				if (position == limit) require(1);
				position++;
				chars[charIndex] = (char)((b & 0x1F) << 6 | byteBuffer.get() & 0x3F);
				break;
			case 14:
				require(2);
				position += 2;
				int b2 = byteBuffer.get();
				int b3 = byteBuffer.get();
				chars[charIndex] = (char)((b & 0x0F) << 12 | (b2 & 0x3F) << 6 | b3 & 0x3F);
				break;
			}
			charIndex++;
		}
	}

	private String readAscii () {
		int end = position;
		int start = end - 1;
		int limit = this.limit;
		int b;
		do {
			if (end == limit) return readAscii_slow();
			end++;
			b = byteBuffer.get();
		} while ((b & 0x80) == 0);
		byteBuffer.put(end - 1, (byte)(byteBuffer.get(end - 1) & 0x7F)); // Mask end of ascii bit.
		byte[] tmp = new byte[end - start];
		byteBuffer.position(start);
		byteBuffer.get(tmp);
		String value = new String(tmp, 0, 0, end - start);
		byteBuffer.put(end - 1, (byte)(byteBuffer.get(end - 1) | 0x80));
		position = end;
		byteBuffer.position(position);
		return value;
	}

	private String readAscii_slow () {
		position--; // Re-read the first byte.
		// Copy chars currently in buffer.
		int charCount = limit - position;
		if (charCount > chars.length) chars = new char[charCount * 2];
		char[] chars = this.chars;
		for (int i = position, ii = 0, n = limit; i < n; i++, ii++)
			chars[ii] = (char)byteBuffer.get(i);
		position = limit;
		// Copy additional chars one by one.
		while (true) {
			require(1);
			position++;
			int b = byteBuffer.get();
			if (charCount == chars.length) {
				char[] newChars = new char[charCount * 2];
				System.arraycopy(chars, 0, newChars, 0, charCount);
				chars = newChars;
				this.chars = newChars;
			}
			if ((b & 0x80) == 0x80) {
				chars[charCount++] = (char)(b & 0x7F);
				break;
			}
			chars[charCount++] = (char)b;
		}
		return new String(chars, 0, charCount);
	}

	public StringBuilder readStringBuilder () {
		int available = require(1);
		position++;
		int b = byteBuffer.get();
		if ((b & 0x80) == 0) return new StringBuilder(readAscii()); // ASCII.
		// Null, empty, or UTF8.
		int charCount = available >= 5 ? readUtf8Length(b) : readUtf8Length_slow(b);
		switch (charCount) {
		case 0:
			return null;
		case 1:
			return new StringBuilder("");
		}
		charCount--;
		if (chars.length < charCount) chars = new char[charCount];
		readUtf8(charCount);
		StringBuilder builder = new StringBuilder(charCount);
		builder.append(chars, 0, charCount);
		return builder;
	}

	public float readFloat () throws KryoException {
		require(4);
		position += 4;
		return byteBuffer.getFloat();
	}

	public float readFloat (float precision, boolean optimizePositive) throws KryoException {
		return readInt(optimizePositive) / (float)precision;
	}

	public short readShort () throws KryoException {
		require(2);
		position += 2;
		return byteBuffer.getShort();
	}

	public int readShortUnsigned () throws KryoException {
		require(2);
		position += 2;
		return byteBuffer.getShort();
	}

	public long readLong () throws KryoException {
		require(8);
		position += 8;
		return byteBuffer.getLong();
	}

	public long readLong (boolean optimizePositive) throws KryoException {
		if (require(1) < 9) return readLong_slow(optimizePositive);
		int p = position + 1;
		int b = byteBuffer.get();
		long result = b & 0x7F;
		if ((b & 0x80) != 0) {
			p++;
			b = byteBuffer.get();
			result |= (b & 0x7F) << 7;
			if ((b & 0x80) != 0) {
				p++;
				b = byteBuffer.get();
				result |= (b & 0x7F) << 14;
				if ((b & 0x80) != 0) {
					p++;
					b = byteBuffer.get();
					result |= (b & 0x7F) << 21;
					if ((b & 0x80) != 0) {
						p++;
						b = byteBuffer.get();
						result |= (long)(b & 0x7F) << 28;
						if ((b & 0x80) != 0) {
							p++;
							b = byteBuffer.get();
							result |= (long)(b & 0x7F) << 35;
							if ((b & 0x80) != 0) {
								p++;
								b = byteBuffer.get();
								result |= (long)(b & 0x7F) << 42;
								if ((b & 0x80) != 0) {
									p++;
									b = byteBuffer.get();
									result |= (long)(b & 0x7F) << 49;
									if ((b & 0x80) != 0) {
										p++;
										b = byteBuffer.get();
										result |= (long)b << 56;
									}
								}
							}
						}
					}
				}
			}
		}
		position = p;
		if (!optimizePositive) result = (result >>> 1) ^ -(result & 1);
		return result;
	}

	private long readLong_slow (boolean optimizePositive) {
		// The buffer is guaranteed to have at least 1 byte.
		position++;
		int b = byteBuffer.get();
		long result = b & 0x7F;
		if ((b & 0x80) != 0) {
			require(1);
			position++;
			b = byteBuffer.get();
			result |= (b & 0x7F) << 7;
			if ((b & 0x80) != 0) {
				require(1);
				position++;
				b = byteBuffer.get();
				result |= (b & 0x7F) << 14;
				if ((b & 0x80) != 0) {
					require(1);
					position++;
					b = byteBuffer.get();
					result |= (b & 0x7F) << 21;
					if ((b & 0x80) != 0) {
						require(1);
						position++;
						b = byteBuffer.get();
						result |= (long)(b & 0x7F) << 28;
						if ((b & 0x80) != 0) {
							require(1);
							position++;
							b = byteBuffer.get();
							result |= (long)(b & 0x7F) << 35;
							if ((b & 0x80) != 0) {
								require(1);
								position++;
								b = byteBuffer.get();
								result |= (long)(b & 0x7F) << 42;
								if ((b & 0x80) != 0) {
									require(1);
									position++;
									b = byteBuffer.get();
									result |= (long)(b & 0x7F) << 49;
									if ((b & 0x80) != 0) {
										require(1);
										position++;
										b = byteBuffer.get();
										result |= (long)b << 56;
									}
								}
							}
						}
					}
				}
			}
		}
		if (!optimizePositive) result = (result >>> 1) ^ -(result & 1);
		return result;
	}

	public boolean readBoolean () throws KryoException {
		require(1);
		position++;
		return byteBuffer.get() == 1 ? true : false;
	}

	public char readChar () throws KryoException {
		require(2);
		position += 2;
		return byteBuffer.getChar();
	}

	public double readDouble () throws KryoException {
		require(8);
		position += 8;
		return byteBuffer.getDouble();
	}

	public double readDouble (double precision, boolean optimizePositive) throws KryoException {
		return readLong(optimizePositive) / (double)precision;
	}

	public int[] readInts (int length) throws KryoException {
		if (capacity - position >= length * 4 && byteOrder == nativeOrder) {
			int[] array = new int[length];
			IntBuffer buf = byteBuffer.asIntBuffer();
			buf.get(array);
			position += length * 4;
			byteBuffer.position(position);
			return array;
		} else
			return super.readInts(length);
	}

	public long[] readLongs (int length) throws KryoException {
		if (capacity - position >= length * 8 && byteOrder == nativeOrder) {
			long[] array = new long[length];
			LongBuffer buf = byteBuffer.asLongBuffer();
			buf.get(array);
			position += length * 8;
			byteBuffer.position(position);
			return array;
		} else
			return super.readLongs(length);
	}

	public float[] readFloats (int length) throws KryoException {
		if (capacity - position >= length * 4 && byteOrder == nativeOrder) {
			float[] array = new float[length];
			FloatBuffer buf = byteBuffer.asFloatBuffer();
			buf.get(array);
			position += length * 4;
			byteBuffer.position(position);
			return array;
		} else
			return super.readFloats(length);
	}

	public short[] readShorts (int length) throws KryoException {
		if (capacity - position >= length * 2 && byteOrder == nativeOrder) {
			short[] array = new short[length];
			ShortBuffer buf = byteBuffer.asShortBuffer();
			buf.get(array);
			position += length * 2;
			byteBuffer.position(position);
			return array;
		} else
			return super.readShorts(length);
	}

	public char[] readChars (int length) throws KryoException {
		if (capacity - position >= length * 2 && byteOrder == nativeOrder) {
			char[] array = new char[length];
			CharBuffer buf = byteBuffer.asCharBuffer();
			buf.get(array);
			position += length * 2;
			byteBuffer.position(position);
			return array;
		} else
			return super.readChars(length);
	}

	public double[] readDoubles (int length) throws KryoException {
		if (capacity - position >= length * 8 && byteOrder == nativeOrder) {
			double[] array = new double[length];
			DoubleBuffer buf = byteBuffer.asDoubleBuffer();
			buf.get(array);
			position += length * 8;
			byteBuffer.position(position);
			return array;
		} else
			return super.readDoubles(length);
	}
}
