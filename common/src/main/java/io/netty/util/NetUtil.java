/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.StringTokenizer;

/**
 * A class that holds a number of network-related constants.
 * <p/>
 * This class borrowed some of its methods from a  modified fork of the
 * <a href="http://svn.apache.org/repos/asf/harmony/enhanced/java/branches/java6/classlib/modules/luni/
 * src/main/java/org/apache/harmony/luni/util/Inet6Util.java">Inet6Util class</a> which was part of Apache Harmony.
 */
public final class NetUtil {

    /**
     * The {@link Inet4Address} that represents the IPv4 loopback address '127.0.0.1'
     */
    public static final Inet4Address LOCALHOST4;

    /**
     * The {@link Inet6Address} that represents the IPv6 loopback address '::1'
     */
    public static final Inet6Address LOCALHOST6;

    /**
     * The {@link InetAddress} that represents the loopback address. If IPv6 stack is available, it will refer to
     * {@link #LOCALHOST6}.  Otherwise, {@link #LOCALHOST4}.
     */
    public static final InetAddress LOCALHOST;

    /**
     * The loopback {@link NetworkInterface} of the current machine
     */
    public static final NetworkInterface LOOPBACK_IF;

    /**
     * The SOMAXCONN value of the current machine.  If failed to get the value,  {@code 200}  is used as a
     * default value for Windows or {@code 128} for others.
     */
    public static final int SOMAXCONN;

    /**
     * This defines how many words (represented as ints) are needed to represent an IPv6 address
     */
    private static final int IPV6_WORD_COUNT = 8;

    /**
     * The maximum number of characters for an IPV6 string with no scope
     */
    private static final int IPV6_MAX_CHAR_COUNT = 39;

    /**
     * Number of bytes needed to represent and IPV6 value
     */
    private static final int IPV6_BYTE_COUNT = 16;

    /**
     * Maximum amount of value adding characters in between IPV6 separators
     */
    private static final int IPV6_MAX_CHAR_BETWEEN_SEPARATOR = 4;

    /**
     * Minimum number of separators that must be present in an IPv6 string
     */
    private static final int IPV6_MIN_SEPARATORS = 2;

    /**
     * Maximum number of separators that must be present in an IPv6 string
     */
    private static final int IPV6_MAX_SEPARATORS = 8;

    /**
     * Number of bytes needed to represent and IPV4 value
     */
    private static final int IPV4_BYTE_COUNT = 4;

    /**
     * Maximum amount of value adding characters in between IPV4 separators
     */
    private static final int IPV4_MAX_CHAR_BETWEEN_SEPARATOR = 3;

    /**
     * Number of separators that must be present in an IPv4 string
     */
    private static final int IPV4_SEPARATORS = 3;

    /**
     * {@code true} if IPv4 should be used even if the system supports both IPv4 and IPv6.
     */
    private static final boolean IPV4_PREFERRED = SystemPropertyUtil.getBoolean("java.net.preferIPv4Stack", false);

    /**
     * {@code true} if an IPv6 address should be preferred when a host has both an IPv4 address and an IPv6 address.
     */
    private static final boolean IPV6_ADDRESSES_PREFERRED =
            SystemPropertyUtil.getBoolean("java.net.preferIPv6Addresses", false);

    /**
     * The logger being used by this class
     */
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NetUtil.class);

    static {
        logger.debug("-Djava.net.preferIPv4Stack: {}", IPV4_PREFERRED);
        logger.debug("-Djava.net.preferIPv6Addresses: {}", IPV6_ADDRESSES_PREFERRED);

        byte[] LOCALHOST4_BYTES = {127, 0, 0, 1};
        byte[] LOCALHOST6_BYTES = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

        // Create IPv4 loopback address.
        Inet4Address localhost4 = null;
        try {
            localhost4 = (Inet4Address) InetAddress.getByAddress("localhost", LOCALHOST4_BYTES);
        } catch (Exception e) {
            // We should not get here as long as the length of the address is correct.
            PlatformDependent.throwException(e);
        }
        LOCALHOST4 = localhost4;

        // Create IPv6 loopback address.
        Inet6Address localhost6 = null;
        try {
            localhost6 = (Inet6Address) InetAddress.getByAddress("localhost", LOCALHOST6_BYTES);
        } catch (Exception e) {
            // We should not get here as long as the length of the address is correct.
            PlatformDependent.throwException(e);
        }
        LOCALHOST6 = localhost6;

        // Retrieve the list of available network interfaces.
        List<NetworkInterface> ifaces = new ArrayList<NetworkInterface>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    // Use the interface with proper INET addresses only.
                    if (SocketUtils.addressesFromNetworkInterface(iface).hasMoreElements()) {
                        ifaces.add(iface);
                    }
                }
            }
        } catch (SocketException e) {
            logger.warn("Failed to retrieve the list of available network interfaces", e);
        }

        // Find the first loopback interface available from its INET address (127.0.0.1 or ::1)
        // Note that we do not use NetworkInterface.isLoopback() in the first place because it takes long time
        // on a certain environment. (e.g. Windows with -Djava.net.preferIPv4Stack=true)
        NetworkInterface loopbackIface = null;
        InetAddress loopbackAddr = null;
        loop: for (NetworkInterface iface: ifaces) {
            for (Enumeration<InetAddress> i = SocketUtils.addressesFromNetworkInterface(iface); i.hasMoreElements();) {
                InetAddress addr = i.nextElement();
                if (addr.isLoopbackAddress()) {
                    // Found
                    loopbackIface = iface;
                    loopbackAddr = addr;
                    break loop;
                }
            }
        }

        // If failed to find the loopback interface from its INET address, fall back to isLoopback().
        if (loopbackIface == null) {
            try {
                for (NetworkInterface iface: ifaces) {
                    if (iface.isLoopback()) {
                        Enumeration<InetAddress> i = SocketUtils.addressesFromNetworkInterface(iface);
                        if (i.hasMoreElements()) {
                            // Found the one with INET address.
                            loopbackIface = iface;
                            loopbackAddr = i.nextElement();
                            break;
                        }
                    }
                }

                if (loopbackIface == null) {
                    logger.warn("Failed to find the loopback interface");
                }
            } catch (SocketException e) {
                logger.warn("Failed to find the loopback interface", e);
            }
        }

        if (loopbackIface != null) {
            // Found the loopback interface with an INET address.
            logger.debug(
                    "Loopback interface: {} ({}, {})",
                    loopbackIface.getName(), loopbackIface.getDisplayName(), loopbackAddr.getHostAddress());
        } else {
            // Could not find the loopback interface, but we can't leave LOCALHOST as null.
            // Use LOCALHOST6 or LOCALHOST4, preferably the IPv6 one.
            if (loopbackAddr == null) {
                try {
                    if (NetworkInterface.getByInetAddress(LOCALHOST6) != null) {
                        logger.debug("Using hard-coded IPv6 localhost address: {}", localhost6);
                        loopbackAddr = localhost6;
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    if (loopbackAddr == null) {
                        logger.debug("Using hard-coded IPv4 localhost address: {}", localhost4);
                        loopbackAddr = localhost4;
                    }
                }
            }
        }

        LOOPBACK_IF = loopbackIface;
        LOCALHOST = loopbackAddr;

        // As a SecurityManager may prevent reading the somaxconn file we wrap this in a privileged block.
        //
        // See https://github.com/netty/netty/issues/3680
        SOMAXCONN = AccessController.doPrivileged(new PrivilegedAction<Integer>() {
            @Override
            public Integer run() {
                // Determine the default somaxconn (server socket backlog) value of the platform.
                // The known defaults:
                // - Windows NT Server 4.0+: 200
                // - Linux and Mac OS X: 128
                int somaxconn = PlatformDependent.isWindows() ? 200 : 128;
                File file = new File("/proc/sys/net/core/somaxconn");
                BufferedReader in = null;
                try {
                    // file.exists() may throw a SecurityException if a SecurityManager is used, so execute it in the
                    // try / catch block.
                    // See https://github.com/netty/netty/issues/4936
                    if (file.exists()) {
                        in = new BufferedReader(new FileReader(file));
                        somaxconn = Integer.parseInt(in.readLine());
                        if (logger.isDebugEnabled()) {
                            logger.debug("{}: {}", file, somaxconn);
                        }
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("{}: {} (non-existent)", file, somaxconn);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to get SOMAXCONN from: {}", file, e);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Exception e) {
                            // Ignored.
                        }
                    }
                }
                return somaxconn;
            }
        });
    }

    /**
     * Returns {@code true} if IPv4 should be used even if the system supports both IPv4 and IPv6. Setting this
     * property to {@code true} will disable IPv6 support. The default value of this property is {@code false}.
     *
     * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Java SE
     *      networking properties</a>
     */
    public static boolean isIpV4StackPreferred() {
        return IPV4_PREFERRED;
    }

    /**
     * Returns {@code true} if an IPv6 address should be preferred when a host has both an IPv4 address and an IPv6
     * address. The default value of this property is {@code false}.
     *
     * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html">Java SE
     *      networking properties</a>
     */
    public static boolean isIpV6AddressesPreferred() {
        return IPV6_ADDRESSES_PREFERRED;
    }

    /**
     * Creates an byte[] based on an ipAddressString. No error handling is performed here.
     */
    public static byte[] createByteArrayFromIpAddressString(String ipAddressString) {

        if (isValidIpV4Address(ipAddressString)) {
            StringTokenizer tokenizer = new StringTokenizer(ipAddressString, ".");
            String token;
            int tempInt;
            byte[] byteAddress = new byte[IPV4_BYTE_COUNT];
            for (int i = 0; i < IPV4_BYTE_COUNT; i ++) {
                token = tokenizer.nextToken();
                tempInt = Integer.parseInt(token);
                byteAddress[i] = (byte) tempInt;
            }

            return byteAddress;
        }

        if (isValidIpV6Address(ipAddressString)) {
            if (ipAddressString.charAt(0) == '[') {
                ipAddressString = ipAddressString.substring(1, ipAddressString.length() - 1);
            }

            int percentPos = ipAddressString.indexOf('%');
            if (percentPos >= 0) {
                ipAddressString = ipAddressString.substring(0, percentPos);
            }

            return getIPv6ByName(ipAddressString, true);
        }
        return null;
    }

    private static int getIntValue(char c) {
        switch (c) {
            case '0':
                return 0;
            case '1':
                return 1;
            case '2':
                return 2;
            case '3':
                return 3;
            case '4':
                return 4;
            case '5':
                return 5;
            case '6':
                return 6;
            case '7':
                return 7;
            case '8':
                return 8;
            case '9':
                return 9;
        }

        c = Character.toLowerCase(c);
        switch (c) {
            case 'a':
                return 10;
            case 'b':
                return 11;
            case 'c':
                return 12;
            case 'd':
                return 13;
            case 'e':
                return 14;
            case 'f':
                return 15;
        }
        return 0;
    }

    /**
     * Converts a 32-bit integer into an IPv4 address.
     */
    public static String intToIpAddress(int i) {
        StringBuilder buf = new StringBuilder(15);
        buf.append(i >> 24 & 0xff);
        buf.append('.');
        buf.append(i >> 16 & 0xff);
        buf.append('.');
        buf.append(i >> 8 & 0xff);
        buf.append('.');
        buf.append(i & 0xff);
        return buf.toString();
    }

    /**
     * Converts 4-byte or 16-byte data into an IPv4 or IPv6 string respectively.
     *
     * @throws IllegalArgumentException
     *         if {@code length} is not {@code 4} nor {@code 16}
     */
    public static String bytesToIpAddress(byte[] bytes) {
        return bytesToIpAddress(bytes, 0, bytes.length);
    }

    /**
     * Converts 4-byte or 16-byte data into an IPv4 or IPv6 string respectively.
     *
     * @throws IllegalArgumentException
     *         if {@code length} is not {@code 4} nor {@code 16}
     */
    public static String bytesToIpAddress(byte[] bytes, int offset, int length) {
        switch (length) {
            case 4: {
                return new StringBuilder(15)
                        .append(bytes[offset] & 0xff)
                        .append('.')
                        .append(bytes[offset + 1] & 0xff)
                        .append('.')
                        .append(bytes[offset + 2] & 0xff)
                        .append('.')
                        .append(bytes[offset + 3] & 0xff).toString();
            }
            case 16:
                return toAddressString(bytes, offset, false);
            default:
                throw new IllegalArgumentException("length: " + length + " (expected: 4 or 16)");
        }
    }

    public static boolean isValidIpV6Address(String ipAddress) {
        int length = ipAddress.length();
        boolean doubleColon = false;
        int numberOfColons = 0;
        int numberOfPeriods = 0;
        StringBuilder word = new StringBuilder();
        char c = 0;
        char prevChar;
        int startOffset = 0; // offset for [] ip addresses
        int endOffset = ipAddress.length();

        if (endOffset < 2) {
            return false;
        }

        // Strip []
        if (ipAddress.charAt(0) == '[') {
            if (ipAddress.charAt(endOffset - 1) != ']') {
                return false; // must have a close ]
            }

            startOffset = 1;
            endOffset --;
        }

        // Strip the interface name/index after the percent sign.
        int percentIdx = ipAddress.indexOf('%', startOffset);
        if (percentIdx >= 0) {
            endOffset = percentIdx;
        }

        for (int i = startOffset; i < endOffset; i ++) {
            prevChar = c;
            c = ipAddress.charAt(i);
            switch (c) {
                // case for the last 32-bits represented as IPv4 x:x:x:x:x:x:d.d.d.d
                case '.':
                    numberOfPeriods ++;
                    if (numberOfPeriods > 3) {
                        return false;
                    } else if (numberOfPeriods == 1) {
                        // Verify this address is of the correct structure to contain an IPv4 address.
                        // It must be IPv4-Mapped or IPv4-Compatible
                        // (see https://tools.ietf.org/html/rfc4291#section-2.5.5).
                        int j = i - word.length() - 2; // index of character before the previous ':'.
                        final int beginColonIndex = ipAddress.lastIndexOf(':', j);
                        if (beginColonIndex == -1) {
                            return false;
                        }
                        char tmpChar = ipAddress.charAt(j);
                        if (isValidIPv4MappedChar(tmpChar)) {
                            if (j - beginColonIndex != 4 ||
                                !isValidIPv4MappedChar(ipAddress.charAt(j - 1)) ||
                                !isValidIPv4MappedChar(ipAddress.charAt(j - 2)) ||
                                !isValidIPv4MappedChar(ipAddress.charAt(j - 3))) {
                                return false;
                            }
                            j -= 5;
                        } else if (tmpChar == '0' || tmpChar == ':') {
                            --j;
                        } else {
                            return false;
                        }
                        for (; j >= startOffset; --j) {
                            tmpChar = ipAddress.charAt(j);
                            if (tmpChar != '0' && tmpChar != ':') {
                                return false;
                            }
                        }
                    }
                    if (!isValidIp4Word(word.toString())) {
                        return false;
                    }
                    if (numberOfColons != 6 && !doubleColon) {
                        return false;
                    }
                    word.delete(0, word.length());
                    break;

                case ':':
                    // FIX "IP6 mechanism syntax #ip6-bad1"
                    // An IPV6 address cannot start with a single ":".
                    // Either it can starti with "::" or with a number.
                    if (i == startOffset && (ipAddress.length() <= i || ipAddress.charAt(i + 1) != ':')) {
                        return false;
                    }
                    // END FIX "IP6 mechanism syntax #ip6-bad1"
                    numberOfColons ++;
                    if (numberOfColons > 7) {
                        return false;
                    }
                    if (numberOfPeriods > 0) {
                        return false;
                    }
                    if (prevChar == ':') {
                        if (doubleColon) {
                            return false;
                        }
                        doubleColon = true;
                    }
                    word.delete(0, word.length());
                    break;

                default:
                    if (word != null && word.length() > 3) {
                        return false;
                    }
                    if (!isValidHexChar(c)) {
                        return false;
                    }
                    word.append(c);
            }
        }

        // Check if we have an IPv4 ending
        if (numberOfPeriods > 0) {
            // There is a test case with 7 colons and valid ipv4 this should resolve it
            if (numberOfPeriods != 3 || !(isValidIp4Word(word.toString()) && (numberOfColons < 7 || doubleColon))) {
                return false;
            }
        } else {
            // If we're at then end and we haven't had 7 colons then there is a
            // problem unless we encountered a doubleColon
            if (numberOfColons != 7 && !doubleColon) {
                return false;
            }

            // If we have an empty word at the end, it means we ended in either
            // a : or a .
            // If we did not end in :: then this is invalid
            if (word.length() == 0 && ipAddress.charAt(length - 1 - startOffset) == ':' &&
                ipAddress.charAt(length - 2 - startOffset) != ':') {
                return false;
            }
        }

        return true;
    }

    private static boolean isValidIp4Word(String word) {
        char c;
        if (word.length() < 1 || word.length() > 3) {
            return false;
        }
        for (int i = 0; i < word.length(); i ++) {
            c = word.charAt(i);
            if (!(c >= '0' && c <= '9')) {
                return false;
            }
        }
        return Integer.parseInt(word) <= 255;
    }

    private static boolean isValidHexChar(char c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'F' || c >= 'a' && c <= 'f';
    }

    private static boolean isValidNumericChar(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isValidIPv4MappedChar(char c) {
        return c == 'f' || c == 'F';
    }

    private static boolean isValidIPv4MappedSeparators(byte b0, byte b1, boolean mustBeZero) {
        // We allow IPv4 Mapped (https://tools.ietf.org/html/rfc4291#section-2.5.5.1)
        // and IPv4 compatible (https://tools.ietf.org/html/rfc4291#section-2.5.5.1).
        // The IPv4 compatible is deprecated, but it allows parsing of plain IPv4 addressed into IPv6-Mapped addresses.
        return b0 == b1 && (b0 == 0 || !mustBeZero && b1 == -1);
    }

    private static boolean isValidIPv4Mapped(byte[] bytes, int currentIndex, int compressBegin, int compressLength) {
        return currentIndex <= 12 && currentIndex >= 2 &&
                isValidIPv4MappedSeparators(bytes[currentIndex - 1], bytes[currentIndex - 2],
                (compressBegin + compressLength) >= 14) && PlatformDependent.isZero(bytes, 0, currentIndex - 3);
    }

    /**
     * Takes a string and parses it to see if it is a valid IPV4 address.
     *
     * @return true, if the string represents an IPV4 address in dotted
     *         notation, false otherwise
     */
    public static boolean isValidIpV4Address(String value) {

        int periods = 0;
        int i;
        int length = value.length();

        if (length > 15) {
            return false;
        }
        char c;
        StringBuilder word = new StringBuilder();
        for (i = 0; i < length; i ++) {
            c = value.charAt(i);
            if (c == '.') {
                periods ++;
                if (periods > 3) {
                    return false;
                }
                if (word.length() == 0) {
                    return false;
                }
                if (Integer.parseInt(word.toString()) > 255) {
                    return false;
                }
                word.delete(0, word.length());
            } else if (!Character.isDigit(c)) {
                return false;
            } else {
                if (word.length() > 2) {
                    return false;
                }
                word.append(c);
            }
        }

        if (word.length() == 0 || Integer.parseInt(word.toString()) > 255) {
            return false;
        }

        return periods == 3;
    }

    /**
     * Returns the {@link Inet6Address} representation of a {@link CharSequence} IP address.
     * <p>
     * This method will treat all IPv4 type addresses as "IPv4 mapped" (see {@link #getByName(CharSequence, boolean)})
     * @param ip {@link CharSequence} IP address to be converted to a {@link Inet6Address}
     * @return {@link Inet6Address} representation of the {@code ip} or {@code null} if not a valid IP address.
     */
    public static Inet6Address getByName(CharSequence ip) {
        return getByName(ip, true);
    }

    /**
     * Returns the {@link Inet6Address} representation of a {@link CharSequence} IP address.
     * <p>
     * The {@code ipv4Mapped} parameter specifies how IPv4 addresses should be treated.
     * "IPv4 mapped" format as
     * defined in <a href="http://tools.ietf.org/html/rfc4291#section-2.5.5">rfc 4291 section 2</a> is supported.
     * @param ip {@link CharSequence} IP address to be converted to a {@link Inet6Address}
     * @param ipv4Mapped
     * <ul>
     * <li>{@code true} To allow IPv4 mapped inputs to be translated into {@link Inet6Address}</li>
     * <li>{@code false} Consider IPv4 mapped addresses as invalid.</li>
     * </ul>
     * @return {@link Inet6Address} representation of the {@code ip} or {@code null} if not a valid IP address.
     */
    public static Inet6Address getByName(CharSequence ip, boolean ipv4Mapped) {
        byte[] bytes = getIPv6ByName(ip, ipv4Mapped);
        if (bytes == null) {
            return null;
        }
        try {
            return Inet6Address.getByAddress(null, bytes, -1);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e); // Should never happen
        }
    }

    /**
     * Returns the byte array representation of a {@link CharSequence} IP address.
     * <p>
     * The {@code ipv4Mapped} parameter specifies how IPv4 addresses should be treated.
     * "IPv4 mapped" format as
     * defined in <a href="http://tools.ietf.org/html/rfc4291#section-2.5.5">rfc 4291 section 2</a> is supported.
     * @param ip {@link CharSequence} IP address to be converted to a {@link Inet6Address}
     * @param ipv4Mapped
     * <ul>
     * <li>{@code true} To allow IPv4 mapped inputs to be translated into {@link Inet6Address}</li>
     * <li>{@code false} Consider IPv4 mapped addresses as invalid.</li>
     * </ul>
     * @return byte array representation of the {@code ip} or {@code null} if not a valid IP address.
     */
    private static byte[] getIPv6ByName(CharSequence ip, boolean ipv4Mapped) {
        final byte[] bytes = new byte[IPV6_BYTE_COUNT];
        final int ipLength = ip.length();
        int compressBegin = 0;
        int compressLength = 0;
        int currentIndex = 0;
        int value = 0;
        int begin = -1;
        int i = 0;
        int ipv6Separators = 0;
        int ipv4Separators = 0;
        int tmp;
        boolean needsShift = false;
        for (; i < ipLength; ++i) {
            final char c = ip.charAt(i);
            switch (c) {
            case ':':
                ++ipv6Separators;
                if (i - begin > IPV6_MAX_CHAR_BETWEEN_SEPARATOR ||
                        ipv4Separators > 0 || ipv6Separators > IPV6_MAX_SEPARATORS ||
                        currentIndex + 1 >= bytes.length) {
                    return null;
                }
                value <<= (IPV6_MAX_CHAR_BETWEEN_SEPARATOR - (i - begin)) << 2;

                if (compressLength > 0) {
                    compressLength -= 2;
                }

                // The value integer holds at most 4 bytes from right (most significant) to left (least significant).
                // The following bit shifting is used to extract and re-order the individual bytes to achieve a
                // left (most significant) to right (least significant) ordering.
                bytes[currentIndex++] = (byte) (((value & 0xf) << 4) | ((value >> 4) & 0xf));
                bytes[currentIndex++] = (byte) ((((value >> 8) & 0xf) << 4) | ((value >> 12) & 0xf));
                tmp = i + 1;
                if (tmp < ipLength && ip.charAt(tmp) == ':') {
                    ++tmp;
                    if (compressBegin != 0 || (tmp < ipLength && ip.charAt(tmp) == ':')) {
                        return null;
                    }
                    ++ipv6Separators;
                    needsShift = ipv6Separators == 2 && value == 0;
                    compressBegin = currentIndex;
                    compressLength = bytes.length - compressBegin - 2;
                    ++i;
                }
                value = 0;
                begin = -1;
                break;
            case '.':
                ++ipv4Separators;
                tmp = i - begin; // tmp is the length of the current segment.
                if (tmp > IPV4_MAX_CHAR_BETWEEN_SEPARATOR
                        || begin < 0
                        || ipv4Separators > IPV4_SEPARATORS
                        || (ipv6Separators > 0 && (currentIndex + compressLength < 12))
                        || i + 1 >= ipLength
                        || currentIndex >= bytes.length
                        || ipv4Separators == 1 &&
                            // We also parse pure IPv4 addresses as IPv4-Mapped for ease of use.
                            ((!ipv4Mapped || currentIndex != 0 && !isValidIPv4Mapped(bytes, currentIndex,
                                                                                     compressBegin, compressLength)) ||
                                (tmp == 3 && (!isValidNumericChar(ip.charAt(i - 1)) ||
                                              !isValidNumericChar(ip.charAt(i - 2)) ||
                                              !isValidNumericChar(ip.charAt(i - 3))) ||
                                 tmp == 2 && (!isValidNumericChar(ip.charAt(i - 1)) ||
                                              !isValidNumericChar(ip.charAt(i - 2))) ||
                                 tmp == 1 && !isValidNumericChar(ip.charAt(i - 1))))) {
                    return null;
                }
                value <<= (IPV4_MAX_CHAR_BETWEEN_SEPARATOR - tmp) << 2;

                // The value integer holds at most 3 bytes from right (most significant) to left (least significant).
                // The following bit shifting is to restructure the bytes to be left (most significant) to
                // right (least significant) while also accounting for each IPv4 digit is base 10.
                begin = (value & 0xf) * 100 + ((value >> 4) & 0xf) * 10 + ((value >> 8) & 0xf);
                if (begin < 0 || begin > 255) {
                    return null;
                }
                bytes[currentIndex++] = (byte) begin;
                value = 0;
                begin = -1;
                break;
            default:
                if (!isValidHexChar(c) || (ipv4Separators > 0 && !isValidNumericChar(c))) {
                    return null;
                }
                if (begin < 0) {
                    begin = i;
                } else if (i - begin > IPV6_MAX_CHAR_BETWEEN_SEPARATOR) {
                    return null;
                }
                // The value is treated as a sort of array of numbers because we are dealing with
                // at most 4 consecutive bytes we can use bit shifting to accomplish this.
                // The most significant byte will be encountered first, and reside in the right most
                // position of the following integer
                value += getIntValue(c) << ((i - begin) << 2);
                break;
            }
        }

        final boolean isCompressed = compressBegin > 0;
        // Finish up last set of data that was accumulated in the loop (or before the loop)
        if (ipv4Separators > 0) {
            if (begin > 0 && i - begin > IPV4_MAX_CHAR_BETWEEN_SEPARATOR ||
                    ipv4Separators != IPV4_SEPARATORS ||
                    currentIndex >= bytes.length) {
                return null;
            }
            if (ipv6Separators == 0) {
                compressLength = 12;
            } else if (ipv6Separators >= IPV6_MIN_SEPARATORS &&
                           (!isCompressed && (ipv6Separators == 6 && ip.charAt(0) != ':') ||
                            isCompressed && (ipv6Separators < IPV6_MAX_SEPARATORS &&
                                             (ip.charAt(0) != ':' || compressBegin <= 2)))) {
                compressLength -= 2;
            } else {
                return null;
            }
            value <<= (IPV4_MAX_CHAR_BETWEEN_SEPARATOR - (i - begin)) << 2;

            // The value integer holds at most 3 bytes from right (most significant) to left (least significant).
            // The following bit shifting is to restructure the bytes to be left (most significant) to
            // right (least significant) while also accounting for each IPv4 digit is base 10.
            begin = (value & 0xf) * 100 + ((value >> 4) & 0xf) * 10 + ((value >> 8) & 0xf);
            if (begin < 0 || begin > 255) {
                return null;
            }
            bytes[currentIndex++] = (byte) begin;
        } else {
            tmp = ipLength - 1;
            if (begin > 0 && i - begin > IPV6_MAX_CHAR_BETWEEN_SEPARATOR ||
                    ipv6Separators < IPV6_MIN_SEPARATORS ||
                    !isCompressed && (ipv6Separators + 1 != IPV6_MAX_SEPARATORS  ||
                                      ip.charAt(0) == ':' || ip.charAt(tmp) == ':') ||
                    isCompressed && (ipv6Separators > IPV6_MAX_SEPARATORS ||
                        (ipv6Separators == IPV6_MAX_SEPARATORS &&
                          (compressBegin <= 2 && ip.charAt(0) != ':' ||
                           compressBegin >= 14 && ip.charAt(tmp) != ':'))) ||
                    currentIndex + 1 >= bytes.length) {
                return null;
            }
            if (begin >= 0 && i - begin <= IPV6_MAX_CHAR_BETWEEN_SEPARATOR) {
                value <<= (IPV6_MAX_CHAR_BETWEEN_SEPARATOR - (i - begin)) << 2;
            }
            // The value integer holds at most 4 bytes from right (most significant) to left (least significant).
            // The following bit shifting is used to extract and re-order the individual bytes to achieve a
            // left (most significant) to right (least significant) ordering.
            bytes[currentIndex++] = (byte) (((value & 0xf) << 4) | ((value >> 4) & 0xf));
            bytes[currentIndex++] = (byte) ((((value >> 8) & 0xf) << 4) | ((value >> 12) & 0xf));
        }

        i = currentIndex + compressLength;
        if (needsShift || i >= bytes.length) {
            // Right shift array
            if (i >= bytes.length) {
                ++compressBegin;
            }
            for (i = currentIndex; i < bytes.length; ++i) {
                for (begin = bytes.length - 1; begin >= compressBegin; --begin) {
                    bytes[begin] = bytes[begin - 1];
                }
                bytes[begin] = 0;
                ++compressBegin;
            }
        } else {
            // Selectively move elements
            for (i = 0; i < compressLength; ++i) {
                begin = i + compressBegin;
                currentIndex = begin + compressLength;
                if (currentIndex < bytes.length) {
                    bytes[currentIndex] = bytes[begin];
                    bytes[begin] = 0;
                } else {
                    break;
                }
            }
        }

        if (ipv4Separators > 0) {
            // We only support IPv4-Mapped addresses [1] because IPv4-Compatible addresses are deprecated [2].
            // [1] https://tools.ietf.org/html/rfc4291#section-2.5.5.2
            // [2] https://tools.ietf.org/html/rfc4291#section-2.5.5.1
            bytes[10] = bytes[11] = (byte) 0xff;
        }

        return bytes;
    }

    /**
     * Returns the {@link String} representation of an {@link InetSocketAddress}.
     * <p>
     * The output does not include Scope ID.
     * @param addr {@link InetSocketAddress} to be converted to an address string
     * @return {@code String} containing the text-formatted IP address
     */
    public static String toSocketAddressString(InetSocketAddress addr) {
        String port = String.valueOf(addr.getPort());
        final StringBuilder sb;

        if (addr.isUnresolved()) {
            String hostString = PlatformDependent.javaVersion() >= 7 ? addr.getHostString() : addr.getHostName();
            sb = newSocketAddressStringBuilder(hostString, port, !isValidIpV6Address(hostString));
        } else {
            InetAddress address = addr.getAddress();
            String hostString = toAddressString(address);
            sb = newSocketAddressStringBuilder(hostString, port, address instanceof Inet4Address);
        }
        return sb.append(':').append(port).toString();
    }

    /**
     * Returns the {@link String} representation of a host port combo.
     */
    public static String toSocketAddressString(String host, int port) {
        String portStr = String.valueOf(port);
        return newSocketAddressStringBuilder(
                host, portStr, !isValidIpV6Address(host)).append(':').append(portStr).toString();
    }

    private static StringBuilder newSocketAddressStringBuilder(String host, String port, boolean ipv4) {
        int hostLen = host.length();
        if (ipv4) {
            // Need to include enough space for hostString:port.
            return new StringBuilder(hostLen + 1 + port.length()).append(host);
        }
        // Need to include enough space for [hostString]:port.
        StringBuilder stringBuilder = new StringBuilder(hostLen + 3 + port.length());
        if (hostLen > 1 && host.charAt(0) == '[' && host.charAt(hostLen - 1) == ']') {
            return stringBuilder.append(host);
        }
        return stringBuilder.append('[').append(host).append(']');
    }

    /**
     * Returns the {@link String} representation of an {@link InetAddress}.
     * <ul>
     * <li>Inet4Address results are identical to {@link InetAddress#getHostAddress()}</li>
     * <li>Inet6Address results adhere to
     * <a href="http://tools.ietf.org/html/rfc5952#section-4">rfc 5952 section 4</a></li>
     * </ul>
     * <p>
     * The output does not include Scope ID.
     * @param ip {@link InetAddress} to be converted to an address string
     * @return {@code String} containing the text-formatted IP address
     */
    public static String toAddressString(InetAddress ip) {
        return toAddressString(ip, false);
    }

    /**
     * Returns the {@link String} representation of an {@link InetAddress}.
     * <ul>
     * <li>Inet4Address results are identical to {@link InetAddress#getHostAddress()}</li>
     * <li>Inet6Address results adhere to
     * <a href="http://tools.ietf.org/html/rfc5952#section-4">rfc 5952 section 4</a> if
     * {@code ipv4Mapped} is false.  If {@code ipv4Mapped} is true then "IPv4 mapped" format
     * from <a href="http://tools.ietf.org/html/rfc4291#section-2.5.5">rfc 4291 section 2</a> will be supported.
     * The compressed result will always obey the compression rules defined in
     * <a href="http://tools.ietf.org/html/rfc5952#section-4">rfc 5952 section 4</a></li>
     * </ul>
     * <p>
     * The output does not include Scope ID.
     * @param ip {@link InetAddress} to be converted to an address string
     * @param ipv4Mapped
     * <ul>
     * <li>{@code true} to stray from strict rfc 5952 and support the "IPv4 mapped" format
     * defined in <a href="http://tools.ietf.org/html/rfc4291#section-2.5.5">rfc 4291 section 2</a> while still
     * following the updated guidelines in
     * <a href="http://tools.ietf.org/html/rfc5952#section-4">rfc 5952 section 4</a></li>
     * <li>{@code false} to strictly follow rfc 5952</li>
     * </ul>
     * @return {@code String} containing the text-formatted IP address
     */
    public static String toAddressString(InetAddress ip, boolean ipv4Mapped) {
        if (ip instanceof Inet4Address) {
            return ip.getHostAddress();
        }
        if (!(ip instanceof Inet6Address)) {
            throw new IllegalArgumentException("Unhandled type: " + ip);
        }

        return toAddressString(ip.getAddress(), 0, ipv4Mapped);
    }

    private static String toAddressString(byte[] bytes, int offset, boolean ipv4Mapped) {
        final int[] words = new int[IPV6_WORD_COUNT];
        int i;
        final int end = offset + words.length;
        for (i = offset; i < end; ++i) {
            words[i] = ((bytes[i << 1] & 0xff) << 8) | (bytes[(i << 1) + 1] & 0xff);
        }

        // Find longest run of 0s, tie goes to first found instance
        int currentStart = -1;
        int currentLength = 0;
        int shortestStart = -1;
        int shortestLength = 0;
        for (i = 0; i < words.length; ++i) {
            if (words[i] == 0) {
                if (currentStart < 0) {
                    currentStart = i;
                }
            } else if (currentStart >= 0) {
                currentLength = i - currentStart;
                if (currentLength > shortestLength) {
                    shortestStart = currentStart;
                    shortestLength = currentLength;
                }
                currentStart = -1;
            }
        }
        // If the array ends on a streak of zeros, make sure we account for it
        if (currentStart >= 0) {
            currentLength = i - currentStart;
            if (currentLength > shortestLength) {
                shortestStart = currentStart;
                shortestLength = currentLength;
            }
        }
        // Ignore the longest streak if it is only 1 long
        if (shortestLength == 1) {
            shortestLength = 0;
            shortestStart = -1;
        }

        // Translate to string taking into account longest consecutive 0s
        final int shortestEnd = shortestStart + shortestLength;
        final StringBuilder b = new StringBuilder(IPV6_MAX_CHAR_COUNT);
        if (shortestEnd < 0) { // Optimization when there is no compressing needed
            b.append(Integer.toHexString(words[0]));
            for (i = 1; i < words.length; ++i) {
                b.append(':');
                b.append(Integer.toHexString(words[i]));
            }
        } else { // General case that can handle compressing (and not compressing)
            // Loop unroll the first index (so we don't constantly check i==0 cases in loop)
            final boolean isIpv4Mapped;
            if (inRangeEndExclusive(0, shortestStart, shortestEnd)) {
                b.append("::");
                isIpv4Mapped = ipv4Mapped && (shortestEnd == 5 && words[5] == 0xffff);
            } else {
                b.append(Integer.toHexString(words[0]));
                isIpv4Mapped = false;
            }
            for (i = 1; i < words.length; ++i) {
                if (!inRangeEndExclusive(i, shortestStart, shortestEnd)) {
                    if (!inRangeEndExclusive(i - 1, shortestStart, shortestEnd)) {
                        // If the last index was not part of the shortened sequence
                        if (!isIpv4Mapped || i == 6) {
                            b.append(':');
                        } else {
                            b.append('.');
                        }
                    }
                    if (isIpv4Mapped && i > 5) {
                        b.append(words[i] >> 8);
                        b.append('.');
                        b.append(words[i] & 0xff);
                    } else {
                        b.append(Integer.toHexString(words[i]));
                    }
                } else if (!inRangeEndExclusive(i - 1, shortestStart, shortestEnd)) {
                    // If we are in the shortened sequence and the last index was not
                    b.append("::");
                }
            }
        }

        return b.toString();
    }

    /**
     * Does a range check on {@code value} if is within {@code start} (inclusive) and {@code end} (exclusive).
     * @param value The value to checked if is within {@code start} (inclusive) and {@code end} (exclusive)
     * @param start The start of the range (inclusive)
     * @param end The end of the range (exclusive)
     * @return
     * <ul>
     * <li>{@code true} if {@code value} if is within {@code start} (inclusive) and {@code end} (exclusive)</li>
     * <li>{@code false} otherwise</li>
     * </ul>
     */
    private static boolean inRangeEndExclusive(int value, int start, int end) {
        return value >= start && value < end;
    }

    /**
     * A constructor to stop this class being constructed.
     */
    private NetUtil() {
        // Unused
    }
}
