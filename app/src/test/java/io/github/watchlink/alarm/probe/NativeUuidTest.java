package io.github.watchlink.alarm.probe;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeUuidTest {
 @Test public void missingAndMalformedStayUnknown()throws Exception {for(String s:new String[]{null,"","label","00000000-0000-0000-0000-000000000000"," 0123456789abcdef0123456789abcdef"}) assertNull(NativeUuid.digest(s));}
 @Test public void sameUuidSurvivesCaseAndProcessChanges()throws Exception {assertEquals(NativeUuid.digest("ABCDEF0123456789ABCDEF0123456789"),NativeUuid.digest("abcdef0123456789abcdef0123456789"));}
 @Test public void differentNativeIdsAndDomainsAreSeparated()throws Exception {String s="abcdef0123456789abcdef0123456789";assertNotEquals(NativeUuid.digest(s),NativeUuid.digest("bbcdef0123456789abcdef0123456789"));assertNotEquals(NativeUuid.digest(s),DeviceToken.of(s));}
}
