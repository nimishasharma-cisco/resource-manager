package com.tailf.pkg.nsoutil;

import java.io.IOException;
import java.net.Socket;
import org.apache.log4j.Logger;

import com.tailf.conf.*;
import com.tailf.maapi.*;
import com.tailf.ncs.*;

import com.tailf.ncs.ns.NcsAlarms;
import com.tailf.conf.ConfException;
import com.tailf.navu.NavuException;
import java.io.IOException;
import com.tailf.ncs.alarmman.common.ManagedDevice;
import com.tailf.ncs.alarmman.common.ManagedObject;
import com.tailf.ncs.alarmman.common.PerceivedSeverity;
import com.tailf.ncs.alarmman.producer.AlarmSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class NSOUtil {
    private static final Logger LOGGER = Logger.getLogger(NSOUtil.class);
    /**
     * Pre-requirement: HA mode is enabled.
     */
    public static boolean isMaster(Maapi maapi, int tid)

        throws ConfException, IOException {

        ConfEnumeration ha_mode_enum =  (ConfEnumeration)
            maapi.getElem(tid, "/tfnm:ncs-state/ha/mode");

        String ha_mode =
            ConfEnumeration.getLabelByEnum(
                   "/tfnm:ncs-state/ha/mode",
                   ha_mode_enum);

        if ("master".equals(ha_mode)) {
            return true;
        }

        // slave or relay-slave or none
        return false;
    }

    public static boolean isHaEnabled(Maapi maapi, int tid)
        throws ConfException, IOException {

        return maapi.exists(tid, "/tfnm:ncs-state/ha");
    }


    /**
     * Redeploy a service.
     * The service pointed to in <code>path</code> will be re-deployed
     * using one of the actions <code>touch</code>,
     * <code>reactive-re-deploy</code> or <code>re-deploy</code> depending
     * on NSO version used.
     * The redeploy takes place in a new thread making this method safe
     * to call from a CDB subscriber.
     * The action will be called using the user <code>admin</code>
     * and the context <code>system</code>.
     *
     * @param path Path to the service to redeploy.
     */
    public static void redeploy(String path) {
        redeploy(path, "admin");
    }

    /**
     * Redeploy a service.
     * The service pointed to in <code>path</code> will be re-deployed
     * using one of the actions <code>touch</code>,
     * <code>reactive-re-deploy</code> or <code>re-deploy</code> depending
     * on NSO version used.
     * The redeploy takes place in a new thread making this method safe
     * to call from a CDB subscriber.
     * The action will be called using the specified <code>user</code>
     * and the context <code>system</code>.
     *
     * @param path Path to the service to redeploy.
     * @param user Name of the user used for the redeploy session.
     */
    public static void redeploy(String path, String user) {
        Set<ToRedeploy> redeps = new HashSet<ToRedeploy>();
        redeps.add(new ToRedeploy(path, user));
        redeploy(redeps);
    }

    /**
     * Redeploy some services.
     * All services in in the <code>toRedeploy</code> set will be
     * re-deployed using the corresponding <code>user</code>.
     * Each service will be re-deployed using one of the actions
     * <code>touch</code>, <code>reactive-re-deploy</code> or
     * <code>re-deploy</code> depending on NSO version used.
     * The redeploy takes place in a new thread making this method safe
     * to call from a CDB subscriber.
     * The action will be called using the specified user and
     * the context <code>system</code>.
     *
     * @param toRedeploy A Set of ToRedeploy instances.
     */
    public static void redeploy(Set<ToRedeploy> toRedeploy) {
        Redeployer r = new Redeployer(toRedeploy);
        Thread t = new Thread(r);
        t.start();
    }

    private static class Redeployer implements Runnable {
        private Maapi redepMaapi;
        private Socket redepSock;
        private Map<String, List<String> > redeps =
                                        new HashMap<String, List<String> >();
        private String actionFmt;
        private boolean trans = false;
 
        private ConfIdentityRef serviceActivationAlarm =
                                    new ConfIdentityRef(new NcsAlarms().hash(),
                                    NcsAlarms._service_activation_failure);

        public Redeployer(Set<ToRedeploy> toRedeploy) {
            try {
                /* set up Maapi socket */
                redepSock = new Socket(NcsMain.getInstance().getNcsHost(),
                                       NcsMain.getInstance().getNcsPort());
                redepMaapi = new Maapi(redepSock);

                /* set up action depending on NSO version */
                if (Conf.LIBVSN >= 0x06020000) {
                    actionFmt = "%s/touch";
                    trans = true;
                }
                else if (Conf.LIBVSN >= 0x06010000) {
                    actionFmt = "%s/reactive-re-deploy";
                }
                else {
                    actionFmt = "%s/re-deploy";
                }

                /* map user -> services */
                for (ToRedeploy item : toRedeploy) {
                    String user = item.getUsername();
                    List<String> l = redeps.get(user);
                    if (l == null) {
                        l = new ArrayList<String>();
                        redeps.put(user, l);
                    }
                    l.add(item.getAllocatingService());
                }

            } catch (Exception e) {
                LOGGER.error("redeployer exception", e);
            }
        }


        public void run() {
            try {
                for (String user : this.redeps.keySet()) {
                    redepMaapi.startUserSession(user,
                                    redepMaapi.getSocket().getInetAddress(),
                                    "system",
                                    new String[] {},
                                    MaapiUserSessionFlag.PROTO_TCP);
                    int tid = -1;

                    for (String path : this.redeps.get(user)) {
                        if (trans) {
                            tid = redepMaapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ_WRITE);
                        }

                        LOGGER.info(String.format("re-deploying %s as user %s (tid: %d)", path, user, tid));
                        if (tid != -1) {
                            redepMaapi.requestActionTh(tid, new ConfXMLParam[] {},
                                                    String.format(this.actionFmt, path));
                        } else {
                            redepMaapi.requestAction(new ConfXMLParam[] {},
                                                    String.format(this.actionFmt, path));
                        }

                        if (tid != -1) {
                            try {
                              redepMaapi.applyTrans(tid, false);
                              redepMaapi.finishTrans(tid);
                            } catch (Exception e) {
                              LOGGER.error(String.format("Error in service re-deploy for %s", path));
                              updateAlarm(path, e.getMessage(),
                                        serviceActivationAlarm,
                                        PerceivedSeverity.CRITICAL);
                            }
                            tid = -1;
                        }
                    }
                    redepMaapi.endUserSession();
                }
            } catch (Exception e) {
                LOGGER.error("error in re-deploy", e);
            }
            finally {
                try {
                    redepSock.close();
                }
                catch (IOException e) {
                    // ignore
                }
            }
        }

        protected void updateAlarm(String path, String reason,
                                 ConfIdentityRef alarmType,
                                 PerceivedSeverity severity) {
            AlarmSink sink = new AlarmSink();

            LOGGER.info(String.format("Raising alarm for redeploy failure for %s", path));
            ManagedDevice managedDevice = new ManagedDevice("ncs");
            ManagedObject managedObject = null;

            try {
                // Convert path to ConfPath then to ManagedObject
                managedObject = new ManagedObject(new ConfPath(path));

                sink.submitAlarm(managedDevice,
                                 managedObject,
                                 alarmType,
                                 new ConfBuf(""),
                                 severity,
                                 reason,
                                 null, /* No impacted objects */
                                 null, /* No related alarms */
                                 null, /* No root cause objects */
                                 ConfDatetime.getConfDatetime());
            } catch (NavuException ne) {
                LOGGER.error(String.format("Error trying to raise alarm %s", ne));
            } catch (ConfException ce) {
                LOGGER.error(String.format("Error trying to raise alarm %s", ce));
            } catch (IOException ce) {
                LOGGER.error(String.format("Error trying to raise alarm %s", ce));
            }
        }
    }
}
