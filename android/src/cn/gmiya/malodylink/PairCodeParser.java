package cn.gmiya.malodylink;

import java.util.*;
import java.util.regex.Pattern;

/** Strict dialog-only parsing. Never accepts a code from arbitrary event text. */
public final class PairCodeParser {
    private static final Pattern CODE=Pattern.compile("[0-9]{6}|[0-9]{3}[ \\u00a0][0-9]{3}");
    public static boolean settingsPackage(String name){return "com.android.settings".equals(name)||"com.vivo.settings".equals(name);}
    public static String extract(List<String> values,boolean pairingCodeField){
        StringBuilder context=new StringBuilder();Set<String> codes=new HashSet<>();for(String value:values){if(value==null)continue;String text=value.trim();context.append(text.toLowerCase(Locale.ROOT).replace(" ","")).append('\n');if(CODE.matcher(text).matches())codes.add(text.replace(" ","").replace("\u00a0",""));}
        String labels=context.toString();boolean label=labels.contains("wlan配对码")||labels.contains("wi-fi配对码")||labels.contains("wifi配对码")||labels.contains("wi-fipairingcode")||labels.contains("wifipairingcode")||labels.contains("wirelesspairingcode");
        return (pairingCodeField||label)&&codes.size()==1?codes.iterator().next():null;
    }
}
