DIR           := ../../../../../..
LUX_TEST_DIRS := $(shell pwd)

TEST_NCS_DIR := $(DIR)/test/ncs

include $(DIR)/Makefile

all: test

.PHONY: clean hasetup stop_nodes

# init target to be called from within the lux test file.
#
init: hasetup

clean:: stop_nodes init-cleaning
	rm -fr ./lux_logs
	rm -f ./ncs.conf


# Borrowed from ncs HA basic test.
# But skip using dedicated ncs directories for the single HA node used.
# Instead using the normal /ncs directory under /test.
# With ncs.conf.tmpl the ncs.conf is to generated and copied to /ncs directory.

export NNODES = 1
HAPORT       = 4564
NETCONF_BASE = 2023
IPC_BASE     = 4569
CLI_BASE     = 2122
OPER_ALWAYS  = <operational><enabled>true</enabled><replication>always</replication></operational>
OPER_SYNC    = <operational><enabled>true</enabled><replicationMode>sync</replicationMode></operational>
OPERATIONAL  = <operational><enabled>true</enabled></operational>
EXTENA       = false
REPLICATION  = sync		# async
HATIMEOUT    = PT20S
STARTUP      = false
CANDIDATE    = true
REPLAY	     = false
AES256		 := 		# no AES256 key for older versions

ifeq ($(NCS_VERSION_SHORT),5.3)
	AES256 := <AES256CFB128><key>0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef</key></AES256CFB128>
endif


hasetup: init-cleaning
	@i=0; while [ $$i -lt $(NNODES) ]; do				\
		echo "setting up $(TEST_NCS_DIR)" ;			\
		mkdir -p $(TEST_NCS_DIR)/ssh; \
		chmod 400 ssh_host_dsa_key ;				\
		cp ssh_host_dsa_key $(TEST_NCS_DIR)/ssh/. ;		\
		cp ssh_host_dsa_key.pub $(TEST_NCS_DIR)/ssh/authorized_keys ;	\
		eval HAPORT=`echo '$$'HAPORT_$$i`;			\
		test "x$$HAPORT" = x && HAPORT=$(HAPORT);		\
		sed -e "s|%%HAPORT%%|$$HAPORT|"				\
	            -e "s|%%NETCONFPORT%%|$$(($(NETCONF_BASE)+$$i))|"	\
	            -e "s|%%IPCPORT%%|$$(($(IPC_BASE)+$$i))|"		\
	            -e "s|%%CLIPORT%%|$$(($(CLI_BASE)+$$i))|"		\
		    -e "s|%%REPLICATION%%|$(REPLICATION)|"		\
		    -e "s|<!--%%OP%%-->|$(OPERATIONAL)|"		\
		    -e "s|%%EXTENA%%|$(EXTENA)|"			\
		    -e "s|%%EXTADDR%%|`pwd`/node$$i/socket|"		\
		    -e "s|%%HAEXTADDR%%|`pwd`/node$$i/hasock|"		\
		    -e "s|%%HATIMEOUT%%|$(HATIMEOUT)|"			\
		    -e "s|%%STARTUP%%|$(STARTUP)|"			\
		    -e "s|%%CANDIDATE%%|$(CANDIDATE)|"			\
		    -e "s|%%REPLAY%%|$(REPLAY)|"			\
		    -e "s|%NAME%|node$$i|g"				\
		    -e "s|%%AES256%%|$(AES256)|g"				\
			< ncs.conf.tmpl > ./ncs.conf ;                  \
		i=$$(($$i + 1)) ;					\
	     done

init-cleaning:
	rm -f $(TEST_NCS_DIR)/ssh/ssh_host_dsa_key 
	rm -f $(TEST_NCS_DIR)/ssh/authorized_keys/ssh_host_dsa_key.pub

stop_nodes:
	i=0; \
	while [ $$i -lt $(NNODES) ]; do \
	    env NCS_IPC_PORT=$$(($(IPC_BASE)+$$i)) ncs --stop > /dev/null;\
	    i=$$(($$i + 1)) ; \
	done
	if [ -d netsim ]; then ncs-netsim stop ; fi || true

stop_node%:
	env NCS_IPC_PORT=$$(($(IPC_BASE)+$*)) ncs --stop

