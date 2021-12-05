package in.succinct.beckn.registry.controller;

import com.venky.core.string.StringUtil;
import com.venky.core.util.ObjectUtil;
import com.venky.geo.GeoCoordinate;
import com.venky.swf.controller.ModelController;
import com.venky.swf.controller.VirtualModelController;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.Database;
import com.venky.swf.integration.IntegrationAdaptor;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.plugins.collab.db.model.CryptoKey;
import com.venky.swf.plugins.collab.db.model.config.City;
import com.venky.swf.plugins.collab.db.model.config.Country;
import com.venky.swf.routing.Config;
import com.venky.swf.views.View;
import in.succinct.beckn.BecknObject;
import in.succinct.beckn.Location;
import in.succinct.beckn.Request;
import in.succinct.beckn.registry.db.model.Subscriber;
import in.succinct.beckn.registry.db.model.onboarding.NetworkRole;
import in.succinct.beckn.registry.db.model.onboarding.OperatingRegion;
import in.succinct.beckn.registry.db.model.onboarding.ParticipantKey;
import in.succinct.beckn.registry.extensions.AfterSaveParticipantKey.OnSubscribe;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SubscribersController extends VirtualModelController<Subscriber> {
    public SubscribersController(Path path) {
        super(path);
    }

    @RequireLogin(false)
    public <T> View subscribe() throws Exception{
        String payload = StringUtil.read(getPath().getInputStream());
        Request request = new Request(payload);
        Map<String,String> params = request.extractAuthorizationParams("Authorization",getPath().getHeaders());
        if (params.isEmpty()){
            throw new RuntimeException("Signature Verification failed");
        }

        String unique_key_id = params.get("unique_key_id");
        String subscriber_id = params.get("subscriber_id");
        NetworkRole role =  NetworkRole.find(subscriber_id);
        ParticipantKey signedWithKey = ParticipantKey.find(unique_key_id);
        if (!signedWithKey.isVerified()){
            throw new RuntimeException("Your signing key is not verified by the registrar! Please contact registrar or sign with a verified key.");
        }
        if (!ObjectUtil.equals(role.getNetworkParticipantId() ,signedWithKey.getNetworkParticipantId())){
            throw new RuntimeException("Key signed with is not registered against you. Please contact registrar");
        }
        if (!request.verifySignature("Authorization",getPath().getHeaders(),true)){
            throw new RuntimeException("Signature Verification failed");
        }

        List<Subscriber> subscribers = getIntegrationAdaptor().readRequest(getPath());
        if (subscribers.isEmpty()){
            if (!ObjectUtil.equals(role.getStatus(),NetworkRole.SUBSCRIBER_STATUS_SUBSCRIBED)){
                TaskManager.instance().executeAsync(new OnSubscribe(role),false);
            }
            Subscriber subscriber = Database.getTable(Subscriber.class).newRecord();
            subscriber.setStatus(role.getStatus());
            return getReturnIntegrationAdaptor().createResponse(getPath(),subscriber,Arrays.asList("STATUS"));
        }else {
            for (Subscriber subscriber : subscribers){
                if (!ObjectUtil.isVoid(subscriber.getSubscriberId())){
                    if (!ObjectUtil.equals(subscriber.getSubscriberId(),role.getSubscriberId())){
                        throw new RuntimeException("Cannot sign for a different subscriber!");
                    }
                }else{
                    subscriber.setSubscriberId(role.getSubscriberId());
                }

                ParticipantKey newKey = null;
                if (!ObjectUtil.isVoid(subscriber.getUniqueKeyId())){
                    newKey = ParticipantKey.find(subscriber.getUniqueKeyId());
                    if (!newKey.getRawRecord().isNewRecord()){
                        throw new RuntimeException("Cannot modify a registered key. Please create a new key.");
                    }
                }
                if (newKey != null && (newKey.getRawRecord().isNewRecord() || !newKey.isVerified())){
                    newKey.setEncrPublicKey(subscriber.getEncrPublicKey());
                    newKey.setSigningPublicKey(subscriber.getSigningPublicKey());
                    newKey.setVerified(false);
                    newKey.setNetworkParticipantId(role.getNetworkParticipantId());
                    newKey.setValidFrom(new Timestamp(BecknObject.TIMESTAMP_FORMAT.parse(subscriber.getValidFrom()).getTime()));
                    newKey.setValidUntil(new Timestamp(BecknObject.TIMESTAMP_FORMAT.parse(subscriber.getValidUntil()).getTime()));
                    newKey.save();
                    TaskManager.instance().executeAsync(new OnSubscribe(newKey),false);
                }
                if (!ObjectUtil.isVoid(subscriber.getSubscriberUrl())){
                    role.setUrl(subscriber.getSubscriberUrl());
                }
                if (!ObjectUtil.isVoid(subscriber.getDomain()) && !ObjectUtil.equals(subscriber.getDomain(),role.getNetworkDomain().getName())){
                    throw  new RuntimeException("Cannot change your domain!. you need to register with the registrar for the right domains.");
                }
                if (!ObjectUtil.isVoid(subscriber.getType()) && !ObjectUtil.equals(subscriber.getType(),role.getType())){
                    throw  new RuntimeException("Cannot change your role/type!. you need to register with the registrar for the right roles you wish to participate in your domain.");
                }
                if (role.isDirty()){
                    if (ObjectUtil.equals(role.getStatus(),NetworkRole.SUBSCRIBER_STATUS_SUBSCRIBED)){
                        if (newKey != null){
                            throw new RuntimeException("Cannot create a new  key and modify your subscription in the same call.");
                        }
                    }
                    role.setStatus(NetworkRole.SUBSCRIBER_STATUS_INITIATED);
                    role.save();
                    TaskManager.instance().executeAsync(new OnSubscribe(role),false);
                }
                subscriber.setStatus(role.getStatus());
                loadRegion(subscriber,role);
            }
            return getReturnIntegrationAdaptor().createResponse(getPath(),subscribers,Arrays.asList("STATUS"));
        }

    }
    public void loadRegion(Subscriber subscriber,NetworkRole role){
        OperatingRegion region = Database.getTable(OperatingRegion.class).newRecord();
        region.setNetworkRoleId(role.getId());
        if (!ObjectUtil.isVoid(subscriber.getCity())){
            region.setCityId(City.findByCode(subscriber.getCity()).getId());
            region.setCountryId(region.getCity().getState().getCountryId());
        }else if (!ObjectUtil.isVoid(subscriber.getCountry())){
            region.setCountryId(Country.findByISO(subscriber.getCountry()).getId());
        }
        if (!ObjectUtil.isVoid(subscriber.getLat()) && !ObjectUtil.isVoid(subscriber.getLng()) && !ObjectUtil.isVoid(subscriber.getRadius()) ) {
            region.setLat(subscriber.getLat());
            region.setLng(subscriber.getLng());
            region.setRadius(subscriber.getRadius());
        }
        region = Database.getTable(OperatingRegion.class).getRefreshed(region);
        region.save();

    }

    @RequireLogin(false)
    public <T> View lookup(){
        List<Subscriber> subscribers = getIntegrationAdaptor().readRequest(getPath());

        List<Subscriber> records = Subscriber.lookup(subscribers.get(0),MAX_LIST_RECORDS,getWhereClause());

        return getIntegrationAdaptor().createResponse(getPath(),records,Arrays.asList("KEY_ID","SUBSCRIBER_ID","SUBSCRIBER_URL","TYPE","DOMAIN",
                "CITY","COUNTRY","SIGNING_PUBLIC_KEY","ENCR_PUBLIC_KEY","VALID_FROM","VALID_UNTIL","STATUS","CREATED","UPDATED"));

    }

    @RequireLogin(false)
    public View generateSignatureKeys(){
        CryptoKey key = Database.getTable(CryptoKey.class).newRecord();

        String[] pair = CryptoKey.generateKeyPair(Request.SIGNATURE_ALGO,Request.SIGNATURE_ALGO_KEY_LENGTH);
        key.setPrivateKey(pair[0]);
        key.setPublicKey(pair[1]);
        return IntegrationAdaptor.instance(CryptoKey.class,getIntegrationAdaptor().getFormatClass()).
                createResponse(getPath(),key, Arrays.asList("PUBLIC_KEY","PRIVATE_KEY"));

    }
    @RequireLogin(false)
    public View generateEncryptionKeys(){
        CryptoKey key = Database.getTable(CryptoKey.class).newRecord();

        String[] pair = CryptoKey.generateKeyPair(Request.ENCRYPTION_ALGO,Request.ENCRYPTION_ALGO_KEY_LENGTH);
        key.setPrivateKey(pair[0]);
        key.setPublicKey(pair[1]);
        return IntegrationAdaptor.instance(CryptoKey.class,getIntegrationAdaptor().getFormatClass()).
                createResponse(getPath(),key, Arrays.asList("PUBLIC_KEY","PRIVATE_KEY"));

    }

    @RequireLogin(false)
    public View register_location() throws  Exception {
        return update_location(true);
    }
    @RequireLogin(false)
    public View deregister_location() throws Exception {
        return update_location(false);
    }
    private View update_location(boolean active) throws Exception {
        String payload = StringUtil.read(getPath().getInputStream());

        Request request = new Request(payload);
        if (Config.instance().getBooleanProperty("beckn.auth.enabled", false) &&
                !request.verifySignature("Authorization",getPath().getHeaders())) {
            throw new RuntimeException("Cannot identify Subscriber");
        }

        Map<String, String> authParams = request.extractAuthorizationParams("Authorization",getPath().getHeaders());
        String subscriberId = Request.getSubscriberId(authParams);
        if (subscriberId == null) {
            throw new RuntimeException("Cannot identify Subscriber");
        }
        NetworkRole networkRole = NetworkRole.find(subscriberId);

        Location location = new Location(payload);

        OperatingRegion region = Database.getTable(OperatingRegion.class).newRecord();
        region.setNetworkRoleId(networkRole.getId());
        if (location.getCountry() != null){
            com.venky.swf.plugins.collab.db.model.config.Country country = com.venky.swf.plugins.collab.db.model.config.Country.findByISO(location.getCountry().getCode());
            if (country != null){
                region.setCountryId(country.getId());
            }
        }
        if (location.getCity() != null) {
            com.venky.swf.plugins.collab.db.model.config.City city =
                    com.venky.swf.plugins.collab.db.model.config.City.findByCode(location.getCity().getCode());
            if (city != null) {
                region.setCityId(city.getId());
                region.setCountryId(city.getState().getCountryId());
            }
        }
        GeoCoordinate coordinate = location.getGps();
        if (coordinate != null){
            region.setLat(coordinate.getLat());
            region.setLng(coordinate.getLng());
        }
        if (location.getCircle() != null){
            region.setRadius(location.getCircle().getRadius());
        }

        region = Database.getTable(OperatingRegion.class).getRefreshed(region);
        region.save();
        return getIntegrationAdaptor().createStatusResponse(getPath(),null,"Location Information Updated!");
    }
}
