/* ServerInft.java */

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.*;
import java.rmi.Naming;

// Indicates remote interface description
public interface ServerIntf extends Remote {
             public FileInfo getChunk( String path, long offset )
                           throws RemoteException;

             public FileInfo getFile( String path )
                           throws RemoteException;

             public void setChunk( FileInfo f, long offset )
                           throws RemoteException;

             public void setFile( FileInfo f )
                           throws RemoteException;

             public int getVersionID( String path )
                           throws RemoteException;
                           
             public int unlink( String path )
                           throws RemoteException;

}
