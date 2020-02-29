/* FileInfo.java */

import java.io.*;

// indicates object that can be copied remotely
public class FileInfo implements Serializable {
    public String path;
    public Boolean isFile;
    public int versionID;
    public byte[] filedata;

    public FileInfo() {}

    // for directory
    public FileInfo( String path ) {
        this.isFile = false;
        this.path = path;
        this.filedata = null;
        this.versionID = -1;
    }

    // for file
    public FileInfo( String path, byte[] filedata, int versionID ) {
        this.isFile = true;
        this.path = path;
        this.filedata = filedata;
        this.versionID = versionID;
    }
}
