/* ServerInft.java */

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.*;
import java.rmi.Naming;

// Indicates remote interface description
public interface ServerIntf extends Remote {
             public FileInfo getFile( String path )
                           throws RemoteException;

             public void setFile( FileInfo f )
                           throws RemoteException;

             public int getVersionID( String path )
                           throws RemoteException;
                           
}
