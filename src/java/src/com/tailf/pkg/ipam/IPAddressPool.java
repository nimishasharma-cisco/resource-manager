package com.tailf.pkg.ipam;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Set;

import org.apache.log4j.Logger;

import com.tailf.conf.ConfException;
import com.tailf.conf.ConfIdentityRef;
import com.tailf.navu.NavuNode;
import com.tailf.pkg.ipaddressallocator.AllocationsSet;
import com.tailf.pkg.ipaddressallocator.AvailablesSet;
import com.tailf.pkg.ipaddressallocator.namespaces.ipaddressAllocator;
import com.tailf.pkg.ipam.exceptions.AddressNotAllocatedException;
import com.tailf.pkg.ipam.exceptions.AddressPoolEmptyException;
import com.tailf.pkg.ipam.exceptions.AddressPoolException;
import com.tailf.pkg.ipam.exceptions.AddressPoolMaskInvalidException;
import com.tailf.pkg.ipam.exceptions.AddressRequestNotAvailableException;
import com.tailf.pkg.ipam.exceptions.InvalidNetmaskException;
import com.tailf.pkg.ipam.util.InetAddressRangeSet;
import com.tailf.pkg.nsoutil.Pool;

public class IPAddressPool extends Pool implements Serializable {

    private static Logger LOGGER = Logger.getLogger(IPAddressPool.class);

    private static final long serialVersionUID = 0;
    private Set<Subnet> subnets; /*
                                  * Original Subnets, avoid handing out
                                  * /32 and /128 network and broadcast
                                  *  addresses from these networks
                                  */
    private Set<Subnet> availables;
    private Set<Allocation> allocations;

    private String name;

    public IPAddressPool(String name,
                         Set<Subnet> availables,
                         Set<Allocation> allocations,
                         Set<Subnet> subnets) {
        super(name, new ConfIdentityRef(ipaddressAllocator.hash,
                                    ipaddressAllocator._ip_address_pool_exhausted),
              new ConfIdentityRef(ipaddressAllocator.hash,
                      ipaddressAllocator._ip_address_pool_low_threshold_reached),
              false, 10);

        this.name = name;
        this.availables = availables;
        this.allocations = allocations;
        this.subnets = subnets;
    }

    public String getName() {
        return name;
    }

    public synchronized Allocation allocate(int cidr,
                                            String owner,
                                            String username,
                                            String requestId)
        throws AddressPoolException {
        return this.allocate(cidr,
                             cidr,
                             owner,
                             username,
                             requestId);
    }

    public synchronized Allocation allocate(int cidr4,
                                            int cidr6,
                                            String owner,
                                            String username,
                                            String requestId)
        throws AddressPoolException {
        /*
         * Iterate through available subnets. The set is ordered from
         * narrowest to widest so we will choose the narrowest subnet
         * that fits the requested size.
         */
        for (Subnet availableSubnet : availables) {
            int cidr;

            InetAddress address = availableSubnet.getAddress();
            if (address instanceof Inet4Address) {
                cidr = cidr4;
            } else if (address instanceof Inet6Address) {
                cidr = cidr6;
            } else {
                throw new Error("Unsupported IP version");
            }

            if (availableSubnet.getCIDRMask() == cidr &&
                notNetworkBroadcast(availableSubnet, cidr)) {
                availables.remove(availableSubnet);
                allocations.add(new Allocation(availableSubnet, owner, username, requestId));
                reviewAlarms();
                return new Allocation(availableSubnet, owner, username, requestId);
            } else if (availableSubnet.getCIDRMask() < cidr) {
                return allocateFrom(availableSubnet, cidr, owner, username, requestId);
            }
        }

        /* If we get here, then there is no room in the pool for the requested subnet */

        StringBuffer availMasks = new StringBuffer();
        for (Subnet availSubnet : availables) {
            int msk = availSubnet.getCIDRMask();
            if ((msk != 30) && (msk != 31) && (msk != 32)) {
                availMasks.append(msk + " ");
            }
        }
        if (availMasks.length() == 0) { // Empty pool
            LOGGER.debug("Availables is empty!");
            reviewAlarms();
            throw new AddressPoolEmptyException();
        } else {
            String err = String.format("Requested subnet is too big. Available prefix lengths: %s",
                                       availMasks.toString());
            throw new AddressPoolMaskInvalidException(err);
        }
    }

    public synchronized Allocation allocate(Subnet requestSubnet, String owner, String username,
            String requestId, boolean syncMode, NavuNode opRoot) throws AddressPoolException {

        boolean requestFound = false;
        InetAddress requestAddress = requestSubnet.getAddress();
        int requestCidr = requestSubnet.getCIDRMask();

        /*
         * check if the request subnet is a broadcast address of any of the
         * pools' subnets
         */
        if (!requestAddress.isAnyLocalAddress()
                && !notNetworkBroadcast(requestSubnet, requestCidr)) {
            String err = String.format("Requested subnet %s/%d is a broadcast address in pool %s.",
                    requestAddress.toString(), requestCidr, name);
            LOGGER.debug(err);
            throw new AddressRequestNotAvailableException(err);
        }

        /*
         * Iterate through available subnets. The set is ordered from narrowest
         * to widest so we will choose the narrowest subnet that fits the
         * requested size.
         *
         * If the request contains a subnet address different than ANY
         * ("0.0.0.0" for ipv4 or "::" for ipv6), we're looking for the
         * available subnet which contains the requested address.
         */
        for (Subnet availableSubnet : availables) {
            if (availableSubnet.contains(requestSubnet) || requestAddress.isAnyLocalAddress()) {
                requestFound = true;
                try {
                    if (availableSubnet.getCIDRMask() == requestCidr
                            && notNetworkBroadcast(availableSubnet, requestCidr)) {
                        if (!syncMode) {
                            availables.remove(availableSubnet);
                        } else {
                            // Remove availables right here...
                            ((AvailablesSet) availables).removeSyncMode(opRoot, availableSubnet);
                        }
                        Allocation a = new Allocation(availableSubnet, owner, username, requestId);

                        if (!syncMode) {
                            allocations.add(a);
                        } else {
                            // Allocate right here...
                            ((AllocationsSet) allocations).addSyncMode(opRoot, a);
                        }
                        reviewAlarms();

                        return a;
                    } else if (availableSubnet.getCIDRMask() < requestCidr) {
                        return allocateFrom(availableSubnet, requestSubnet, owner, username,
                                requestId, syncMode, opRoot);
                    }

                } catch (ConfException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                if (!requestAddress.isAnyLocalAddress()) {
                    break;
                }
            }
        }

        /*
         * If we get here, then there is no room in the pool for the requested
         * subnet, or there's no available subnet containing the requested one.
         */
        StringBuffer availMasks = new StringBuffer();
        for (Subnet availSubnet : availables) {
            int msk = availSubnet.getCIDRMask();
            if ((msk != 30) && (msk != 31) && (msk != 32)) {
                availMasks.append(msk + " ");
            }
        }

        if (availMasks.length() == 0) { // Empty pool
            LOGGER.debug("Availables is empty!");
            reviewAlarms();
            throw new AddressPoolEmptyException();
        } else {
            String err = null;
            if (requestFound) {
                err = String.format("Requested subnet is too big. Available prefix lengths: %s",
                        availMasks.toString());
                LOGGER.debug(err);
                throw new AddressPoolMaskInvalidException(err);
            } else {
                err = String.format("Requested subnet %s/%d not available.",
                        requestAddress.toString(), requestCidr);
                LOGGER.debug(err);
                throw new AddressRequestNotAvailableException(err);
            }
        }
    }

    public synchronized Allocation allocate(Subnet requestSubnet, String owner, String username,
            String requestId) throws AddressPoolException {

        return allocate(requestSubnet, owner, username, requestId, false, null);
    }

    private boolean notNetworkBroadcast(Subnet net, int cidr) {
        InetAddress a = net.getAddress();

        if (((a instanceof Inet4Address) && cidr != 32) ||
            ((a instanceof Inet6Address) && cidr != 128)) {
            return true;
        }

        for(Subnet sub : subnets) {
            InetAddress na = sub.getAddress();
            InetAddress ba = sub.getBroadcast();
            if (na instanceof Inet6Address) {
                if (sub.getCIDRMask() > 126) {
                    /* Don't worry about broadcast for such small networks */
                    continue;
                }
                if (na.equals(a) || ba.equals(a)) {
                    return false;
                }
            } else {
                if (sub.getCIDRMask() > 30) {
                    /* Don't worry about broadcast for such small networks */
                    continue;
                }
                if (na.equals(a) || ba.equals(a)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Allocation allocateFrom(Subnet source, int request,
                                    String owner, String username, String requestId) {

        assert(source.getCIDRMask() <= request);

        /* In any case, source subnet will no longer be available */
        availables.remove(source);
        if (source.getCIDRMask() == request) {
            Allocation a = new Allocation(source, owner, username, requestId);
            allocations.add(a);
            reviewAlarms();
            return a;
        }

        /* Split source, make the two halves available, and recurse on the first half. */
        try {
            boolean isIpv6 = source.getAddress() instanceof Inet6Address;
            Subnet[] subs = source.size() == 2 ? source.split4into2() : source.split();// split a /30 into 2x/31 instead of 4x/32
            for (int i = 0; i < subs.length; i++) {
                availables.add(subs[i]);
            }

            int Sub0CIDR = subs[0].getCIDRMask();
            if ((!isIpv6 && request == 32 &&  Sub0CIDR == 32) ||
                ( isIpv6 && request == 128 && Sub0CIDR == 128)) {
                if (notNetworkBroadcast(subs[0], request)) {
                    return allocateFrom(subs[0], request, owner, username, requestId);
                } else {
                    return allocateFrom(subs[1], request, owner, username, requestId);
                }
            } else {
                return allocateFrom(subs[0], request, owner, username, requestId);
            }
        } catch (InvalidNetmaskException e) {
            throw new Error("Internal error, allocation failed", e);
        }
    }

    private Allocation allocateFrom(Subnet source, Subnet requestSubnet,
                                    String owner, String username, String requestId, boolean syncMode, NavuNode opRoot) throws ConfException {

        int         requestCidr     = requestSubnet.getCIDRMask();
        InetAddress requestAddress  = requestSubnet.getAddress();

        assert(source.getCIDRMask() <= requestCidr);
        /* In any case, source subnet will no longer be available */
        if(!syncMode){
            availables.remove(source);
        } else {
            ((AvailablesSet) availables).removeSyncMode(opRoot, source);
        }

        /* the check for source subnet containing the requested subnet, or ANY address aren't really necessary anymore */
        if ((source.getCIDRMask() == requestCidr) &&
            (source.contains(requestSubnet) || requestAddress.isAnyLocalAddress())) {
            Allocation a = new Allocation(source, owner, username, requestId);
            if(!syncMode){
                allocations.add(a);
            } else {
                ((AllocationsSet) allocations).addSyncMode(opRoot, a);
            }
            reviewAlarms();
            return a;
        }

        /* Split source, make the two halves available, and recurse on the half containing the requested subnet. */
        try {
            boolean isIpv6 = source.getAddress() instanceof Inet6Address;
            Subnet[] subs = source.size() == 2 ? source.split4into2() : source.split();// split a /30 into 2x/31 instead of 4x/32

            /* add back all smaller subnets; the matching one will be removed later on */
            for (int i = 0; i < subs.length; i++) {
                if(!syncMode){
                    availables.add(subs[i]);
                } else {
                    ((AvailablesSet) availables).addSyncMode(opRoot, subs[i]);
                }
            }

            int Sub0CIDR = subs[0].getCIDRMask();
            if ((!isIpv6 && requestCidr == 32 &&  Sub0CIDR == 32) ||
                ( isIpv6 && requestCidr == 128 && Sub0CIDR == 128)) {
                if (subs[0].contains(requestSubnet) ||
                   (notNetworkBroadcast(subs[0], requestCidr) && requestAddress.isAnyLocalAddress())) {
                    return allocateFrom(subs[0], requestSubnet, owner, username, requestId, syncMode, opRoot);
                } else {
                    return allocateFrom(subs[1], requestSubnet, owner, username, requestId, syncMode, opRoot);
                }
            } else {
                if(subs[0].contains(requestSubnet) || requestAddress.isAnyLocalAddress()) {
                    return allocateFrom(subs[0], requestSubnet, owner, username, requestId, syncMode, opRoot);
                } else {
                    return allocateFrom(subs[1], requestSubnet, owner, username, requestId, syncMode, opRoot);
                }
            }
        } catch (InvalidNetmaskException e) {
            throw new Error("Internal error, allocation failed", e);
        }
    }

    public synchronized void addToAvailable(Subnet subnet) {

        // If subnet is null, then do not add to available.
        if (subnet == null) {
            return;
        }

        availables.add(subnet);

        /*
         * With IP Address Reservation we now have the situation where
         * the user may allocate subnets and then free portions of the
         * subnets.  The original code (commented out below) did not
         * handle this situation correctly.  For example if the
         * available subnets were 10.1.0.0/32, 10.1.0.1/32,
         * 10.1.0.2/31, 10.1.0.4/30, 10.1.0.8/29, 10.1.0.16/28,
         * 10.1.0.32/27, 10.1.0.64/26 and 10.1.0.128/25, and subnet
         * 10.1.0.0/25 was added to the available pool, it would merge
         * 10.1.0.18/25 and 10.1.0.0/25 into 10.1.0.0/24, without
         * removing the others, so many subnets would be in the
         * available list twice.  Then when for instance 10.1.0.16/28
         * was reserved and removed, 10.1.0.0/24 would still be there,
         * and 10.1.0.0/24 includes 10.1.0.16/28
         */

        /* Put all available addresses into a RangeSet. */
        InetAddressRangeSet rangeSet = new InetAddressRangeSet(availables);

        /* Now copy them into available as subnets. */
        availables.clear();

        try {
            for (Subnet eachSubnet : rangeSet.asSubnetSet()) {
                assert(eachSubnet instanceof Subnet);
                availables.add(eachSubnet);
            }
        } catch (InvalidNetmaskException e1) {
            throw new Error(e1); // Should not happen
        }
    }

    public synchronized void removeFromAvailable(Subnet subnet)
        throws AddressPoolException {

        /* If subnet is null, then do not remove from available. */
        if (subnet == null) {
            return;
        }

        /* Must exactly match an available subnet or be contained in another subnet. */
        if (availables.contains(subnet)) {
            availables.remove(subnet);
        } else {
            /*
             * We did not find an exact match, look for subnet
             * that contains the desired subnet, split and call
             * recursively
             */
            for (Subnet source : availables) {
                if (source.contains(subnet)) {
                    /* Split subnet and remove the part we are looking for */
                    availables.remove(source);
                    assert(source.getCIDRMask() < subnet.getCIDRMask());
                    /*
                     * Split source and put the two halves on the available list
                     * recurse on the half that contains the subnet
                     */
                    try {
                        Subnet[] subs = source.size() == 2 ? source.split4into2() : source.split();// split a /30 into 2x/31 instead of 4x/32

                        for (Subnet s : subs) {
                            availables.add(s);
                        }
                        removeFromAvailable(subnet);
                        return;
                    } catch (InvalidNetmaskException e) {
                        String err = String
                            .format("Address %s is not an available subnet defined by the pool",
                                    subnet);
                        throw new AddressRequestNotAvailableException(err);
                    }
                }
            }

            /* No subnet found, throw error */
            String err =
                String.format("Address %s is not an available subnet defined by the pool", subnet);
            throw new AddressRequestNotAvailableException(err);
        }
    }

    public synchronized void release(Allocation allocation) throws AddressPoolException {
        if (!allocations.contains(allocation)) {
            String err = String.format("Allocation %s was not allocated from the pool", allocation);
            throw new AddressNotAllocatedException(err);
        }
        allocations.remove(allocation);
        addToAvailable(allocation.getAllocated());
        reviewAlarms();
    }

    public synchronized void release(InetAddress addr) throws AddressPoolException {
        /* Need to find allocated with this network address. */
        for (Allocation allocated : allocations) {
            if (allocated.getAllocated().getAddress().equals(addr)) {
                release(allocated);
                return;
            }
        }
        /* If we make it here, then the address wasn't found */
        String err = String.format("Address %s was not allocated from the pool", addr);
        throw new AddressNotAllocatedException(err);
    }

    public synchronized void releaseAll() {
        for (Allocation a : allocations) {
            addToAvailable(a.getAllocated());
        }
        allocations.clear();
        reviewAlarms();
    }

    public Collection<Subnet> getAvailables() {
        return availables;
    }

    public Collection<Allocation> getAllocations() {
        return allocations;
    }

    public synchronized void addAllocation(Allocation a) {
        this.allocations.add(a);
    }

    public synchronized void clearAllocations() {
        this.allocations.clear();
    }

    public boolean isEmpty() {
        return availables.isEmpty();
    }

    public long getNumberOfAvailables () {
        long numberOfAvailables = 0;
        for (Subnet subnet : availables) {
            long sz = subnet.size();
            numberOfAvailables += ((sz == 0) ? 2 : sz); // 2 is for /31 subnets, which are reported as size 0
        }
        return numberOfAvailables;
    }

    public long getTotalSize() {
        long sum = 0;
        for (Subnet subnet : subnets) {
            long sz = subnet.size();
            sum += ((sz == 0) ? 2 : sz); // 2 is for /31 subnets, which are reported as size 0
        }
        return sum;
    }

    public boolean isIpv4() {
        if(subnets.isEmpty()) {
            return false;
        }

        InetAddress address = subnets.iterator().next().getAddress();
        return (address instanceof Inet4Address);
    }

    public boolean isIpv6() {
        if(subnets.isEmpty()) {
            return false;
        }

        InetAddress address = subnets.iterator().next().getAddress();
        return (address instanceof Inet6Address);
    }
}
