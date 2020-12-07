import ncs
from ncs.application import Service
import resource_manager.id_allocator as id_allocator


class LoopService(Service):
    @Service.create
    def cb_create(self, tctx, root, service, proplist):
        self.log.info('Service create(service=', service._path, ')')
        pool_name = service.pool
        alloc_name = service.name
        id_allocator.id_request(service,
                                "/services/id-vl:id-loop-python[name='%s']" % (service.name),
                                tctx.username,
                                pool_name,
                                alloc_name,
                                False)

        id = id_allocator.id_read(tctx.username, root,
                                  pool_name, alloc_name)
        if not id:
            self.log.info("Alloc not ready")
            return

        self.log.info('id = %s' % (id))

        id_allocator.id_request(service,
                                "/services/id-vl:id-loop-python[name='%s']" % (service.name),
                                tctx.username,
                                pool_name,
                                "specific_id",
                                False,
                                20)

        id = id_allocator.id_read(tctx.username, root,
                                  pool_name, "specific_id")
        if not id:
            self.log.info("Alloc not ready")
            return
        self.log.info('id = %s' % (id))

class IdPoolCreateService(Service):
    @Service.create
    def cb_create(self, tctx, root, service, proplist):
        self.log.info('ID_POOL_CREATE_SERVICE 1: Service create(service=', service._path, ')')

        self.log.info("service = {}".format(dir(service)))
        self.log.info("id-pool-create-service name == {}".format(service.name))

        pool_name = service.pool

        idpool = root.ralloc__resource_pools.idalloc__id_pool
        idpool.create(pool_name)

        mypool = idpool[pool_name]
        mypool_range = mypool.range
        mypool_range.start = service.range.start
        mypool_range.end = service.range.end

        self.log.info('ID_POOL_CREATE_SERVICE: Finished.')

class Loop(ncs.application.Application):
    def setup(self):
        self.log.info('Loop RUNNING')
        self.register_service('id-loopspnt-python', LoopService)
        self.register_service('id-pool-create-python', IdPoolCreateService)

    def teardown(self):
        self.log.info('Loop FINISHED')
