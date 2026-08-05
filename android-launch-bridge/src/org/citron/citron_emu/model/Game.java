package org.citron.citron_emu.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parcel-compatible representation of Citron's public Game navigation arg. */
public final class Game implements Parcelable {
    private static final Pattern TITLE_ID = Pattern.compile("(?i)[\\[.]([0-9a-f]{16})[\\].]");

    public final String title;
    public final String path;
    public final String programId;
    public final String developer;
    public final String version;
    public final boolean isHomebrew;

    public Game(String title, String path, String programId,
                String developer, String version, boolean isHomebrew) {
        this.title = title;
        this.path = path;
        this.programId = programId;
        this.developer = developer;
        this.version = version;
        this.isHomebrew = isHomebrew;
    }

    private Game(Parcel in) {
        title = in.readString();
        path = in.readString();
        programId = in.readString();
        developer = in.readString();
        version = in.readString();
        isHomebrew = in.readInt() != 0;
    }

    public static String programIdFromFilename(String filename) {
        Matcher matcher = TITLE_ID.matcher(filename);
        if (!matcher.find()) return "0";
        try {
            return Long.toUnsignedString(Long.parseUnsignedLong(matcher.group(1), 16));
        } catch (NumberFormatException ignored) {
            return "0";
        }
    }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(title);
        out.writeString(path);
        out.writeString(programId);
        out.writeString(developer);
        out.writeString(version);
        out.writeInt(isHomebrew ? 1 : 0);
    }

    public static final Creator<Game> CREATOR = new Creator<Game>() {
        @Override public Game createFromParcel(Parcel in) { return new Game(in); }
        @Override public Game[] newArray(int size) { return new Game[size]; }
    };
}
