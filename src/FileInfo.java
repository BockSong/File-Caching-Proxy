/* FileInfo.java */

import java.io.*;

// indicates object that can be copied remotely
public class FileInfo implements Serializable {
    public String path;
    public Boolean exist; // in case sth wrong
    public Boolean isFile;
    public int versionID;
    public byte[] filedata;

    public FileInfo() {}

    public FileInfo( Boolean ifExist, String path ) {
        this.exist = ifExist;
        this.isFile = false;
        this.path = path;
        this.filedata = null;
        this.versionID = -1;
    }

    public FileInfo( String path, byte[] filedata, int versionID ) {
        this.exist = true;
        this.isFile = true;
        this.path = path;
        this.filedata = filedata;
        this.versionID = versionID;
    }
}
