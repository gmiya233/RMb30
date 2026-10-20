package io.github.muntashirakon.adb;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class AdbUtf8Test {
    public static void main(String[] args)throws Exception {
        for(String destination:new String[]{"shell,v2,raw:ls", "shell,v2,raw:cat '测试/無機質世界.mc'", "shell,v2,raw:cat '🎵/player\u0027s.mc'"}) {
            byte[] encoded=AdbProtocol.generateOpen(7,destination);
            AdbProtocol.Message message=AdbProtocol.Message.parse(new ByteArrayInputStream(encoded),AdbProtocol.A_VERSION_MIN,1048576);
            if(message.command!=AdbProtocol.A_OPEN||message.arg0!=7||message.arg1!=0)throw new AssertionError("OPEN header");
            if(message.dataLength!=destination.getBytes(StandardCharsets.UTF_8).length+1||message.payload[message.dataLength-1]!=0)throw new AssertionError("UTF-8 byte count and terminator");
            if(!destination.equals(new String(message.payload,0,message.dataLength-1,StandardCharsets.UTF_8)))throw new AssertionError("UTF-8 round trip");
        }
        System.out.println("PASS: ASCII, Chinese and emoji OPEN packets round-trip with validated length, checksum and magic");
    }
}
