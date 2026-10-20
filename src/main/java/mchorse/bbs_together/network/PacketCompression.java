package mchorse.bbs_together.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Payload codec used by the mod's crushers. Large payloads (a film workspace is tens of
 * megabytes while its gzipped form is only a few megabytes) are compressed before chunking;
 * small payloads stay raw because gzipping every cursor/color packet would cost more CPU
 * than it saves. The first byte is a format flag, and both endpoints are this mod.
 */
public final class PacketCompression
{
    private static final int FLAG_RAW = 0;
    private static final int FLAG_GZIP = 1;
    /** Smallest payload worth compressing; asset slices (by default 512 KB) stay raw. */
    private static final int COMPRESS_THRESHOLD = 1024 * 1024;
    /** Hard ceiling for gzip output; prevents a tiny compressed payload from inflating to GBs. */
    private static final int MAX_DECOMPRESSED = 256 * 1024 * 1024;

    private PacketCompression()
    {}

    public static byte[] encode(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            /* The crusher's empty-payload sentinel must stay empty so the receiver sees null. */
            return bytes;
        }

        if (bytes.length >= COMPRESS_THRESHOLD)
        {
            try
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 8));

                out.write(FLAG_GZIP);

                try (GZIPOutputStream gzip = new GZIPOutputStream(out))
                {
                    gzip.write(bytes);
                }

                byte[] compressed = out.toByteArray();

                if (compressed.length < bytes.length + 1)
                {
                    return compressed;
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        byte[] raw = new byte[bytes.length + 1];

        raw[0] = FLAG_RAW;
        System.arraycopy(bytes, 0, raw, 1, bytes.length);

        return raw;
    }

    public static byte[] decode(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            return bytes;
        }

        int flag = bytes[0] & 0xFF;

        if (flag == FLAG_GZIP)
        {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes, 1, bytes.length - 1)))
            {
                return readAll(gzip);
            }
            catch (IOException e)
            {
                e.printStackTrace();

                return new byte[0];
            }
        }

        if (flag == FLAG_RAW)
        {
            byte[] raw = new byte[bytes.length - 1];

            System.arraycopy(bytes, 1, raw, 0, raw.length);

            return raw;
        }

        /* Unknown flag: a raw payload from a build without the header. */
        return bytes;
    }

    private static byte[] readAll(InputStream stream) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;

        while ((read = stream.read(buffer)) >= 0)
        {
            if (out.size() > MAX_DECOMPRESSED - read)
            {
                throw new IOException("Decompressed payload exceeds " + MAX_DECOMPRESSED + " bytes");
            }

            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }
}
