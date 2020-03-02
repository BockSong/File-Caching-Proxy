/* FileInfo.java */

import java.io.*;

// indicates object that can be copied remotely
public class FileInfo implements Serializable {
    public String path;
    public Boolean isFile;
    public Boolean isChunking;
    public long length;
    public int versionID;
    public byte[] filedata;

    public FileInfo() {}

    // for directory/file without data
    public FileInfo( String path, Boolean isChunking, long length, int versionID ) {
        this.isChunking = isChunking;
        if (this.isChunking)
            this.isFile = true;
        else
            this.isFile = false;
        this.path = path;
        this.length = length;
        this.filedata = null;
        this.versionID = versionID;
    }

    // for file with data
    public FileInfo( String path, byte[] filedata, int versionID, Boolean isChunking, long length ) {
        this.isFile = true;
        this.path = path;
        this.filedata = filedata;
        this.versionID = versionID;
        this.isChunking = isChunking;
        this.length = length;
    }
}
