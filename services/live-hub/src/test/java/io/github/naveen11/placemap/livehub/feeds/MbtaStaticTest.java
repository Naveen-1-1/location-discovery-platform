package io.github.naveen11.placemap.livehub.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MbtaStaticTest {

    @Test
    void readsRouteNamesDestinationsAndStationsFromTheZip() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            put(zip, "agency.txt", "agency_id\n1\n");
            put(zip, "routes.txt", "﻿route_id,agency_id,route_short_name,route_long_name,route_type\n"
                    + "Red,1,,Red Line,1\n1,1,1,Harvard Square - Nubian Station,3\nGreen-B,1,B,Green Line B,0\n");
            put(zip, "directions.txt", "route_id,direction_id,direction,direction_destination\n"
                    + "Red,0,South,Ashmont/Braintree\nRed,1,North,Alewife\n");
            put(zip, "stops.txt", "stop_id,stop_name,parent_station\nplace-harsq,Harvard,\n70068,Harvard,place-harsq\n");
        }

        MbtaStatic.Names names = MbtaStatic.read(new ByteArrayInputStream(bytes.toByteArray()));

        assertThat(names.route("Red")).isEqualTo("Red Line");
        assertThat(names.route("1")).as("buses go by number").isEqualTo("1");
        assertThat(names.route("Green-B")).as("trains by name").isEqualTo("Green Line B");
        assertThat(names.route("SL9")).isEqualTo("SL9");
        assertThat(names.destination("Red", 1)).isEqualTo("Alewife");
        assertThat(names.station("70068")).isEqualTo("place-harsq");
        assertThat(names.station("place-harsq")).isEqualTo("place-harsq");
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
