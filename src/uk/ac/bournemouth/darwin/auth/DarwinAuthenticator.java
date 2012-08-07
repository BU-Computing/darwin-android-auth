package uk.ac.bournemouth.darwin.auth;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.RSAPrivateKeySpec;

import javax.crypto.*;
import javax.net.ssl.HttpsURLConnection;

import android.accounts.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;


public class DarwinAuthenticator extends AbstractAccountAuthenticator {
  
  private static final int AUTHTOKEN_RETRIEVE_TRY_COUNT = 5;
  private static final String AUTH_BASE_URL = "https://darwin.bournemouth.ac.uk/accounts/";
  public static final String ACCOUNT_TOKEN_TYPE="uk.ac.bournemouth.darwin.auth";
  static final String KEY_PRIVATEKEY = "privatekey";
  static final String KEY_KEYID = "keyid";
  static final String KEY_PUBLICKEY = "publickey";
  private static final String TAG = DarwinAuthenticator.class.getName();
  static final String KEY_ALGORITHM = "RSA";
  static final URI GET_CHALLENGE_URL = URI.create(AUTH_BASE_URL+"challenge");
  static final URI AUTHENTICATE_URL = URI.create(AUTH_BASE_URL+"regkey");
  private static final int CHALLENGE_MAX = 4096;
  private static final String HEADER_RESPONSE = "X-Darwin-Respond";
  private static final int MAX_TOKEN_SIZE = 1024;
  private static final int BASE64_FLAGS = Base64.URL_SAFE|Base64.NO_WRAP;
  public static final String ACCOUNT_TYPE = "darwin";
  private static final int ERRNO_INVALID_TOKENTYPE = 1;
  private Context aContext;

  public DarwinAuthenticator(Context pContext) {
    super(pContext);
    aContext = pContext;
  }

  @Override
  public Bundle addAccount(AccountAuthenticatorResponse pResponse, String pAccountType, String pAuthTokenType, String[] pRequiredFeatures, Bundle pOptions) throws NetworkErrorException {
    if (!(pAuthTokenType==null || ACCOUNT_TOKEN_TYPE.equals(pAuthTokenType))) {
      final Bundle result = new Bundle();
      result.putString(AccountManager.KEY_ERROR_MESSAGE, "invalid authTokenType");
      return result;
    }
    final Intent intent = new Intent(aContext, DarwinAuthenticatorActivity.class);
    intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, pResponse);
    final Bundle bundle = new Bundle();
    bundle.putParcelable(AccountManager.KEY_INTENT, intent);
    return bundle;
  }

  @Override
  public Bundle confirmCredentials(AccountAuthenticatorResponse pResponse, Account pAccount, Bundle pOptions) throws NetworkErrorException {
    final Intent intent = new Intent(aContext, DarwinAuthenticatorActivity.class);
    intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, pResponse);
    intent.putExtra(DarwinAuthenticatorActivity.PARAM_USERNAME, pAccount.name);
    intent.putExtra(DarwinAuthenticatorActivity.PARAM_CONFIRM, true);
    AccountManager am=AccountManager.get(aContext);
    long keyid=Long.parseLong(am.getUserData(pAccount, KEY_KEYID));
    intent.putExtra(DarwinAuthenticatorActivity.PARAM_KEYID, keyid);
    final Bundle bundle = new Bundle();
    bundle.putParcelable(AccountManager.KEY_INTENT, intent);
    return bundle;
  }

  
  
  @Override
  public Bundle updateCredentials(AccountAuthenticatorResponse pResponse, Account pAccount, String pAuthTokenType, Bundle pOptions) throws NetworkErrorException {
    final Intent intent = getUpdateCredentialsBaseIntent(pAccount);
    intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, pResponse);
    AccountManager am=AccountManager.get(aContext);
    long keyid=Long.parseLong(am.getUserData(pAccount, KEY_KEYID));
    intent.putExtra(DarwinAuthenticatorActivity.PARAM_KEYID, keyid);
    final Bundle bundle = new Bundle();
    bundle.putParcelable(AccountManager.KEY_INTENT, intent);
    return bundle;
  }

  private Intent getUpdateCredentialsBaseIntent(Account pAccount) {
    final Intent intent = new Intent(aContext, DarwinAuthenticatorActivity.class);
    intent.putExtra(DarwinAuthenticatorActivity.PARAM_USERNAME, pAccount.name);
    intent.putExtra(DarwinAuthenticatorActivity.PARAM_CONFIRM, false);
    return intent;
  }

  @Override
  public Bundle editProperties(AccountAuthenticatorResponse pResponse, String pAccountType) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException();
  }

  @Override
  public Bundle getAuthToken(AccountAuthenticatorResponse pResponse, Account pAccount, String pAuthTokenType, Bundle pOptions) throws NetworkErrorException {
    if (!pAuthTokenType.equals(ACCOUNT_TOKEN_TYPE)) {
      pResponse.onError(ERRNO_INVALID_TOKENTYPE, "invalid authTokenType");
      return null;
    }
    final AccountManager am = AccountManager.get(aContext);
    String privateKeyString = am.getUserData(pAccount, KEY_PRIVATEKEY);
    RSAPrivateKey privateKey = getPrivateKey(privateKeyString);
    String keyidString = am.getUserData(pAccount, KEY_KEYID);
    long keyid = keyidString==null ? -1l : Long.parseLong(keyidString);
    if (privateKey==null || keyid<0) {
      // We are in an invalid state. We no longer have a private key.
      Bundle result = new Bundle();
      result.putString(AccountManager.KEY_ERROR_MESSAGE, "No private key found associated with account");
      return result;
    }
    int tries=0;
    while (tries<AUTHTOKEN_RETRIEVE_TRY_COUNT) {
      // Get challenge
      try {
        
        String response;
        URI responseUrl;
        ByteBuffer challenge = ByteBuffer.allocate(CHALLENGE_MAX);
        responseUrl = readChallenge(URI.create(GET_CHALLENGE_URL+"?keyid="+keyid), challenge);
        if (challenge!=null) {
          final ByteBuffer responseBuffer = sign(challenge, privateKey);
          response=Base64.encodeToString(responseBuffer.array(), responseBuffer.arrayOffset(), responseBuffer.limit(), BASE64_FLAGS);
        } else {
          // No challenge, update the account.
          Bundle result = new Bundle();
          result.putParcelable(AccountManager.KEY_INTENT, getUpdateCredentialsBaseIntent(pAccount));
          return result;
        }
        HttpsURLConnection c = (HttpsURLConnection) responseUrl.toURL().openConnection();
        
        try {
          c.setDoOutput(true);
          c.setRequestMethod("POST");
          c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf8");
          Writer out = new OutputStreamWriter(c.getOutputStream(), Util.UTF8);
          try {
            out.write("response=");
            out.write(response);
            if (BuildConfig.DEBUG) {
              Log.d(TAG, "Private exponent: "+privateKey.getPrivateExponent()+", priv modulo:"+privateKey.getModulus()+" Response: "+response);
            }
          } finally {
            out.close();
          }
          try {
            ReadableByteChannel in = Channels.newChannel(c.getInputStream());
            try {
              ByteBuffer buffer =ByteBuffer.allocate(MAX_TOKEN_SIZE);
              int count = in.read(buffer);
              if (count<0 || count>=MAX_TOKEN_SIZE) {
                // Can't handle that
              }
              byte[] cookie = new byte[buffer.position()];
              buffer.rewind();
              buffer.get(cookie);
              
              Bundle result = new Bundle();
              result.putString(AccountManager.KEY_ACCOUNT_NAME, pAccount.name);
              result.putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TOKEN_TYPE);
              result.putString(AccountManager.KEY_AUTHTOKEN, new String(cookie, Util.UTF8));
              return result;
              
              
              
            } finally {
              in.close();
            }
          } catch (IOException e) {
            if (c.getResponseCode()!=401) {
              // reauthenticate
              final Intent intent = new Intent(aContext, DarwinAuthenticatorActivity.class);
              intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, pResponse);
              final Bundle bundle = new Bundle();
              bundle.putParcelable(AccountManager.KEY_INTENT, intent);
              return bundle;
              
            }else if (c.getResponseCode()!=404) { // We try again if we didn't get the right code.
              Bundle result = new Bundle();
              result.putInt(AccountManager.KEY_ERROR_CODE, c.getResponseCode());
              result.putString(AccountManager.KEY_ERROR_MESSAGE, e.getMessage());
              return result;
            }
          }
        } finally {
          c.disconnect();
        }
        
      } catch (MalformedURLException e) {
        e.printStackTrace(); // Should never happen, it's a constant
      } catch (IOException e) {
        throw new NetworkErrorException(e);
      }
      ++tries;
    }
    Bundle result = new Bundle();
    result.putString(AccountManager.KEY_ERROR_MESSAGE, "Could not get authentication key");
    return result;
  }

  private URI readChallenge(final URI challengeurl, ByteBuffer challenge) throws IOException, MalformedURLException {
    URI responseUrl;
    HttpsURLConnection c = (HttpsURLConnection) challengeurl.toURL().openConnection();
    c.setInstanceFollowRedirects(false);// We should get the response url.
    try {
      {
        String header = c.getHeaderField(HEADER_RESPONSE);
        responseUrl = header==null ? challengeurl: URI.create(header);
      }
      
      ReadableByteChannel in = Channels.newChannel(c.getInputStream());
      try {
        in.read(challenge);
      } finally {
        in.close();
      }
      challenge.limit(challenge.position());
      challenge.rewind();
    } finally {
      c.disconnect();
    }
    return responseUrl;
  }

  private ByteBuffer sign(ByteBuffer pChallenge, RSAPrivateKey pPrivateKey) {
    Cipher cipher;
    try {
      cipher = Cipher.getInstance("RSA");
      cipher.init(Cipher.ENCRYPT_MODE, pPrivateKey);
      
      byte[] outputraw = new byte[cipher.getOutputSize(pChallenge.limit())];
      
      
      int count = cipher.update(pChallenge.array(), pChallenge.arrayOffset(), pChallenge.remaining(), outputraw);
      count+=cipher.doFinal(outputraw, count);
      
      ByteBuffer output = ByteBuffer.wrap(outputraw, 0, count);
//      output.limit(output.position());
//      output.rewind();
      return output;
    } catch (GeneralSecurityException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  private static RSAPrivateKey getPrivateKey(String pPrivateKeyString) {
    KeyFactory keyfactory;
    try {
      keyfactory = KeyFactory.getInstance(KEY_ALGORITHM);
    } catch (NoSuchAlgorithmException e) {
      Log.e(TAG, "The DSA algorithm isn't supported on your system", e);
      return null;
    }
    KeySpec keyspec;
    {
      int start = 0;
      int end = pPrivateKeyString.indexOf(':');
      BigInteger modulus= new BigInteger(pPrivateKeyString.substring(start, end));
      start = end+1;
      BigInteger privateExponent=new BigInteger(pPrivateKeyString.substring(start));;
      keyspec = new RSAPrivateKeySpec(modulus, privateExponent);
    }
    try {
      return (RSAPrivateKey) keyfactory.generatePrivate(keyspec);
    } catch (InvalidKeySpecException e) {
      Log.w(TAG, "Could not load private key", e);
      return null;
    }
  }
  
  static String encodePrivateKey(RSAPrivateKey pPrivateKey) {
    StringBuilder result = new StringBuilder();
    result.append(pPrivateKey.getModulus());
    result.append(':');
    result.append(pPrivateKey.getPrivateExponent());
    return result.toString();
  }
  
  static String encodePublicKey(RSAPublicKey pPublicKey) {
    StringBuilder result = new StringBuilder();
    result.append(Base64.encodeToString(pPublicKey.getModulus().toByteArray(), BASE64_FLAGS));
    result.append(':');
    result.append(Base64.encodeToString(pPublicKey.getPublicExponent().toByteArray(), BASE64_FLAGS));
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "Registering public key: ("+pPublicKey.getModulus()+", "+pPublicKey.getPublicExponent()+")"+result);
    }
    return result.toString();
  }

  @Override
  public String getAuthTokenLabel(String pAuthTokenType) {
    if (!pAuthTokenType.equals(ACCOUNT_TOKEN_TYPE)) {
      return null;
    }
    return aContext.getString(R.string.authenticator_label);
  }

  @Override
  public Bundle hasFeatures(AccountAuthenticatorResponse pResponse, Account pAccount, String[] pFeatures) throws NetworkErrorException {
    final Bundle result = new Bundle();
    result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
    return result;
  }

}
