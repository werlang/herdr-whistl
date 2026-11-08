package io.github.werlang.whistl.support

import io.github.werlang.whistl.core.rpc.HcClient
import io.github.werlang.whistl.core.rpc.JsonRpcConnection
import io.github.werlang.whistl.core.rpc.LinePipe
import kotlinx.coroutines.test.TestScope

/**
 * Builds an [HcClient] over fake pipes, running its readers on the test's background scope.
 *
 * @param command the command channel's pipe.
 * @param events the events channel's pipe; returned for every subscription.
 * @return a client wired to the given pipes.
 */
fun TestScope.clientWith(
    command: LinePipe = FakeLinePipe(),
    events: LinePipe = FakeLinePipe(),
): HcClient = HcClient(
    command = JsonRpcConnection(command, backgroundScope),
    openEventsPipe = { events },
    scope = backgroundScope,
)
