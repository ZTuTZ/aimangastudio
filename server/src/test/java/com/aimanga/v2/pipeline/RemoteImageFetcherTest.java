package com.aimanga.v2.pipeline;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteImageFetcherTest {

    @Test
    void blocksIpv6UniqueLocalAddresses() throws Exception {
        assertThat(RemoteImageFetcher.isBlockedAddress(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(RemoteImageFetcher.isBlockedAddress(InetAddress.getByName("2001:db8::1"))).isFalse();
    }
}
