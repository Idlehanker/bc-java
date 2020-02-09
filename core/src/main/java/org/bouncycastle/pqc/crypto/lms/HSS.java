package org.bouncycastle.pqc.crypto.lms;

import java.util.List;

import org.bouncycastle.pqc.crypto.ExhaustedPrivateKeyException;
import org.bouncycastle.util.Arrays;

class HSS
{

    public static HSSPrivateKeyParameters generateHSSKeyPair(HSSKeyGenerationParameters parameters)
    {
        //
        // LmsPrivateKey can derive and hold the public key so we just use an array of those.
        //
        LMSPrivateKeyParameters[] keys = new LMSPrivateKeyParameters[parameters.getDepth()];
        LMSSignature[] sig = new LMSSignature[parameters.getDepth() - 1];

        byte[] rootSeed = new byte[32];
        parameters.getRandom().nextBytes(rootSeed);

        byte[] I = new byte[16];
        parameters.getRandom().nextBytes(I);

        //
        // Set the HSS key up with a valid root LMSPrivateKeyParameters and placeholders for the remaining LMS keys.
        // The placeholders pass enough information to allow the HSSPrivateKeyParameters to be properly reset to an
        // index of zero. Rather than repeat the same reset-to-index logic in this static method.
        //

        byte[] zero = new byte[0];

        int hssKeyMaxIndex = 1;
        for (int t = 0; t < keys.length; t++)
        {
            if (t == 0)
            {
                keys[t] = new LMSPrivateKeyParameters(
                    parameters.getLmsParameters()[t].getLmsParam(),
                    parameters.getLmsParameters()[t].getLmOTSParam(),
                    0,
                    I,
                    1 << parameters.getLmsParameters()[t].getLmsParam().getH(),
                    rootSeed);
            }
            else
            {
                keys[t] = new PlaceholderLMSPrivateKey(
                    parameters.getLmsParameters()[t].getLmsParam(),
                    parameters.getLmsParameters()[t].getLmOTSParam(),
                    -1,
                    zero,
                    1 << parameters.getLmsParameters()[t].getLmsParam().getH(),
                    zero);
            }
            hssKeyMaxIndex *= 1 << parameters.getLmsParameters()[t].getLmsParam().getH();
        }

        //
        // New key with dummy values.
        //
        HSSPrivateKeyParameters pk = new HSSPrivateKeyParameters(parameters.getDepth(), Arrays.asList(keys), Arrays.asList(sig), 0, hssKeyMaxIndex, false);

        //
        // Correct Intermediate LMS values will be constructed during reset to index.
        //
        pk.resetKeyToIndex();

        return pk;
    }

    /**
     * Increments an HSS private key without doing any work on it.
     * HSS private keys are automatically incremented when when used to create signatures.
     * <p>
     * The HSS private key is ranged tested before this incrementation is applied.
     * LMS keys will be replaced as required.
     *
     * @param keyPair
     */
    public static void incrementIndex(HSSPrivateKeyParameters keyPair)
    {
        synchronized (keyPair)
        {
            rangeTestKeys(keyPair);
            keyPair.incIndex();
            keyPair.getKeys().get(keyPair.getL() - 1).incIndex();
        }
    }


    static void rangeTestKeys(HSSPrivateKeyParameters keyPair)
    {
        synchronized (keyPair)
        {
            if (keyPair.getIndex() >= keyPair.getIndexLimit())
            {
                throw new ExhaustedPrivateKeyException(
                    "hss private key" +
                        ((keyPair.isLimited()) ? " shard" : "") +
                        " is exhausted");
            }


            int L = keyPair.getL();
            int d = L;
            List<LMSPrivateKeyParameters> prv = keyPair.getKeys();
            while (prv.get(d - 1).getIndex() == 1 << (prv.get(d - 1).getSigParameters().getH()))
            {
                d = d - 1;
                if (d == 0)
                {
                    throw new ExhaustedPrivateKeyException(
                        "hss private key" +
                            ((keyPair.isLimited()) ? " shard" : "") +
                            " is exhausted the maximum limit for this HSS private key");
                }
            }


            while (d < L)
            {
                keyPair.replaceConsumedKey(d);
                d = d + 1;
            }
        }
    }


    public static HSSSignature generateSignature(HSSPrivateKeyParameters keyPair, byte[] message)
    {

        synchronized (keyPair)
        {

            rangeTestKeys(keyPair);

            List<LMSPrivateKeyParameters> keys = keyPair.getKeys();
            List<LMSSignature> sig = keyPair.getSig();
            int L = keyPair.getL();

            LMSPrivateKeyParameters nextKey = keyPair.getKeys().get(L - 1);

            // Step 2. Stand in for sig[L-1]

            LMSSignature signatureResult = LMS.generateSign(nextKey, message);


            int i = 0;
            LMSSignedPubKey[] signed_pub_key = new LMSSignedPubKey[L - 1];
            while (i < L - 1)
            {
                signed_pub_key[i] = new LMSSignedPubKey(
                    sig.get(i),
                    keys.get(i + 1).getPublicKey());
                i = i + 1;
            }

            //
            // increment the index.
            //
            keyPair.incIndex();

            if (L == 1)
            {
                return new HSSSignature(L - 1, signed_pub_key, signatureResult);
            }

            return new HSSSignature(
                L - 1,
                signed_pub_key,
                signatureResult);
        }
    }


//    public static HSSSignature generateSignature(HSSPrivateKeyParameters keyPair, byte[] message)
//    {
//        int L = keyPair.getL();
//
//        //
//        // Algorithm 8
//        //
//        // Step 1.
//        LMSPrivateKeyParameters nextKey = keyPair.getNextSigningKey();
//
//        // Step 2. Stand in for sig[L-1]
//
//        LMSSignature signatureResult = LMS.generateSign(nextKey, message);
//
//        int i = 0;
//        LMSSignedPubKey[] signed_pub_key = new LMSSignedPubKey[L - 1];
//        while (i < L - 1)
//        {
//            signed_pub_key[i] = new LMSSignedPubKey(
//                keyPair.getSig().get(i),
//                keyPair.getKeys().get(i + 1).getPublicKey());
//            i = i + 1;
//        }
//
//        if (L == 1)
//        {
//            return new HSSSignature(L - 1, signed_pub_key, signatureResult);
//        }
//
//        return new HSSSignature(
//            L - 1,
//            signed_pub_key,
//            signatureResult);
//    }


    public static boolean verifySignature(HSSPublicKeyParameters publicKey, HSSSignature signature, byte[] message)
    {
        int Nspk = signature.getlMinus1();
        if (Nspk + 1 != publicKey.getL())
        {
            return false;
        }

        LMSSignature[] sigList = new LMSSignature[Nspk + 1];
        LMSPublicKeyParameters[] pubList = new LMSPublicKeyParameters[Nspk];

        for (int i = 0; i < Nspk; i++)
        {
            sigList[i] = signature.getSignedPubKey()[i].getSignature();
            pubList[i] = signature.getSignedPubKey()[i].getPublicKey();
        }
        sigList[Nspk] = signature.getSignature();

        LMSPublicKeyParameters key = publicKey.getLmsPublicKey();

        for (int i = 0; i < Nspk; i++)
        {
            LMSSignature sig = sigList[i];
            byte[] msg = pubList[i].toByteArray();
            if (!LMS.verifySignature(key, sig, msg))
            {
                return false;
            }
            try
            {
                key = pubList[i];
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        }
        return LMS.verifySignature(key, sigList[Nspk], message);
    }


    static class PlaceholderLMSPrivateKey
        extends LMSPrivateKeyParameters
    {

        public PlaceholderLMSPrivateKey(LMSigParameters lmsParameter, LMOtsParameters otsParameters, int q, byte[] I, int maxQ, byte[] masterSecret)
        {
            super(lmsParameter, otsParameters, q, I, maxQ, masterSecret);
        }

        @Override
        LMOtsPrivateKey getNextOtsPrivateKey()
        {
            throw new RuntimeException("placeholder only");
        }

        @Override
        public LMSPublicKeyParameters getPublicKey()
        {
            throw new RuntimeException("placeholder only");
        }
    }

}
