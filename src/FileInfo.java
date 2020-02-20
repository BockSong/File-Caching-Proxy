/* FileInfo.java */

import java.io.*;

// indicates object that can be copied remotely
public class FileInfo implements Serializable {
    public String path;
    public byte[] filedata;
}
