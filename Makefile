DIR ?= .

NAMESPACE                               := com.tailf.pkg.resourcemanager.namespaces
id-allocator.yang_namespace             := com.tailf.pkg.idallocator.namespaces
id-allocator-oper.yang_namespace        := com.tailf.pkg.idallocator.namespaces
ipaddress-allocator.yang_namespace      := com.tailf.pkg.ipaddressallocator.namespaces
ipaddress-allocator-oper.yang_namespace := com.tailf.pkg.ipaddressallocator.namespaces

TEST_DEPS := cisco-ios
cisco-ios := git ssh://git@stash.tail-f.com/pkg/minimal-cisco-ios.git 149d607a7f658c78aa57e7314ff271b9fc94eff6

LOCAL_TEST_DEPS := id-loop ip-loop ipaddress-allocator-test pool-service

include $(DIR)/package.mk

TEST_VARS += NCS_VERSION_CLEAN=$(NCS_VERSION_CLEAN)
TEST_VARS += NCS_VERSION_SHORT=$(NCS_VERSION_SHORT)
LUX        = $(TEST_VARS) lux

test-lux: test-setup-ncs

package-mk: package-mk-deps

.PHONY: package-mk-deps
package-mk-deps:
	@(cd $(PACKAGE_DIR)/test/packages/id-loop && $(MAKE) package-mk)
	@(cd $(PACKAGE_DIR)/test/packages/ip-loop && $(MAKE) package-mk)
	@(cd $(PACKAGE_DIR)/test/packages/ipaddress-allocator-test && $(MAKE) package-mk)
	@(cd $(PACKAGE_DIR)/test/packages/pool-service && $(MAKE) package-mk)
