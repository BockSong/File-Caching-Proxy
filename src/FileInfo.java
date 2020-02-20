/* FileInfo.java */

import java.io.*;

// indicates object that can be copied remotely
public class FileInfo implements Serializable {
    public String path;
    public Boolean exist;
    public Boolean isFile;
    public int versionID;
    public byte[] filedata;

    public FileInfo() {}

    public FileInfo( Boolean ifExist, String path ) {
        this.exist = ifExist;
        this.isFile = false;
        this.path = path;
        this.filedata = null;
    }

    public FileInfo( String path, byte[] filedata ) {
        this.exist = true;
        this.isFile = true;
        this.path = path;
        this.filedata = filedata;
    }
}
