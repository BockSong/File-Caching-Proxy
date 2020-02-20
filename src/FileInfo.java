/* FileInfo.java */

import java.io.*;

// indicates object that can be copied remotely
public class FileInfo implements Serializable {
    public String path;
    public Boolean exist;
    public byte[] filedata;

    public FileInfo() {}

    public FileInfo( String path ) {
        this.exist = false;
        this.path = path;
        this.filedata = null;
    }

    public FileInfo( String path, byte[] filedata ) {
        this.exist = true;
        this.path = path;
        this.filedata = filedata;
    }
}
