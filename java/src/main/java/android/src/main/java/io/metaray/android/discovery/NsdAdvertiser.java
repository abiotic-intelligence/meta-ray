package io.metaray.android.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import core.model.EmitterConfig;
import core.util.MetaRayException;
import java.util.Objects;

public final class NsdAdvertiser {
  private final NsdManager nsdManager;
  private volatile NsdServiceInfo currentService;
  private volatile NsdManager.RegistrationListener registrationListener;

  public NsdAdvertiser(Context context) {
    Context app = context.getApplicationContext();
    this.nsdManager = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
    if (this.nsdManager == null) {
      throw new MetaRayException("NsdManager unavailable");
    }
  }

  public synchronized void start(EmitterConfig info) {
    Objects.requireNonNull(info, "info");
    stop();
    NsdServiceInfo service = new NsdServiceInfo();
    service.setServiceName(info.instanceId);
    service.setServiceType("_metaray._tcp.");
    service.setPort(info.controlPort);
    putTxt(service, "v", String.valueOf(info.protocolVersion));
    putTxt(service, "app", info.appId);
    putTxt(service, "eid", info.emitterId);
    if (info.emitterKeyId != null) putTxt(service, "ek", info.emitterKeyId);
    if (info.deviceName != null) putTxt(service, "dn", info.deviceName);
    putTxt(service, "codecs", String.join(",", info.codecs));
    putTxt(service, "sec", String.join(",", info.security));
    if (!info.capabilities.isEmpty()) putTxt(service, "cap", String.join(",", info.capabilities));
    if (info.multicastGroup != null) putTxt(service, "mgroup", info.multicastGroup);
    if (info.multicastPort != null) putTxt(service, "mport", String.valueOf(info.multicastPort));

    NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
      @Override public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {}
      @Override public void onRegistrationFailed(NsdServiceInfo nsdServiceInfo, int errorCode) {}
      @Override public void onServiceUnregistered(NsdServiceInfo nsdServiceInfo) {}
      @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
    };
    nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener);
    this.currentService = service;
    this.registrationListener = listener;
  }

  public synchronized void stop() {
    if (registrationListener != null) {
      try {
        nsdManager.unregisterService(registrationListener);
      } catch (RuntimeException ignored) {
      }
    }
    currentService = null;
    registrationListener = null;
  }

  private static void putTxt(NsdServiceInfo service, String key, String value) {
    service.setAttribute(key, value);
  }
}
