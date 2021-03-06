package com.github.chaosal.androidroshead;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;

import org.ros.exception.RemoteException;
import org.ros.exception.ServiceNotFoundException;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.service.ServiceClient;
import org.ros.node.service.ServiceResponseListener;
import org.ros.node.topic.Publisher;

import java.io.File;
import java.io.IOException;

import edu.cmu.pocketsphinx.Assets;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;
import smart_home_core.Command;

public class SpeechRecognitionNode extends AbstractNodeMain implements RecognitionListener {
    private final static String CONTEXT = SpeechRecognitionNode.class.getSimpleName();
    public final static String TOPIC = "/speechRecognition";
    private final static String KWS_SEARCH_NAME = "selfName";
    private final static String GRAMMAR_SEARCH = "grammar";
    private final Context context;
    private SpeechRecognizer recognizer;
    private ToneGenerator toneGenerator;
    private ServiceClient<smart_home_core.CommandRequest, smart_home_core.CommandResponse> commandClient;
    private Publisher<std_msgs.String> speakPublisher;
    private GlobalState globalState;
    private final static int MAX_SCORE = 4000;

    public SpeechRecognitionNode(Context context, GlobalState globalState) {
        this.context = context;
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
        this.globalState = globalState;
    }

    @Override
    public GraphName getDefaultNodeName() {
        return GraphName.of(context.getString(R.string.nodes_prefix) + TOPIC);
    }

    @Override
    public void onStart(ConnectedNode connectedNode) {
        super.onStart(connectedNode);

        try {
            commandClient = connectedNode.newServiceClient(GraphName.of("/command"), Command._TYPE);
            speakPublisher = connectedNode.newPublisher(GraphName.of(context.getString(R.string.nodes_prefix) + SpeakNode.TOPIC), std_msgs.String._TYPE);

            Assets assets = new Assets(context);
            File assetsDir = assets.syncAssets();

            recognizer = SpeechRecognizerSetup.defaultSetup().setAcousticModel(new File(assetsDir, "acc-model"))
                    .setDictionary(new File(assetsDir, "cmudict.dict"))
                            // To disable logging of raw audio comment out this call (takes a lot of space on the device)
                            //.setRawLogDir(assetsDir)
                            // Threshold to tune for keyphrase to balance between false alarms and misses
                    .setKeywordThreshold(1e-25f)
                            // Use context-independent phonetic search, context-dependent is too slow for mobile
                    .setBoolean("-allphone_ci", true)
                    .getRecognizer();

            recognizer.addListener(this);
            recognizer.addKeyphraseSearch(KWS_SEARCH_NAME, context.getString(R.string.recognitionName));
            recognizer.addGrammarSearch(GRAMMAR_SEARCH, new File(assetsDir, "grammar.gram"));

            startListen();
        } catch (IOException e) {
            Log.e("SpeechRecognitionNode", "Filed to run recognizer", e);
        } catch (ServiceNotFoundException e) {
            Log.e("SpeechRecognitionNode", "Filed to found command service", e);
        }
    }

    private void startSearch() {
        recognizer.stop();

        globalState.setListening(true);
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP);
        recognizer.startListening(GRAMMAR_SEARCH, 10000);
        Log.i(CONTEXT, "start search");
    }

    private void startListen() {
        recognizer.stop();
        recognizer.startListening(KWS_SEARCH_NAME);
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onEndOfSpeech() {
        globalState.setListening(false);
        if (!recognizer.getSearchName().equals(KWS_SEARCH_NAME))
            startListen();
    }

    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (hypothesis == null)
            return;

        if (hypothesis.getHypstr().equals(context.getString(R.string.recognitionName)) && !globalState.isSpeaking())
            startSearch();
    }

    @Override
    public void onResult(Hypothesis hypothesis) {
        if (hypothesis == null)
            return;
        Log.i(CONTEXT, String.format("score: %d, probe: %d, result: %s",
                hypothesis.getBestScore(), hypothesis.getProb(), hypothesis.getHypstr()));
        if (Math.abs(hypothesis.getBestScore()) > MAX_SCORE)
            return;

        if (!hypothesis.getHypstr().equals(context.getString(R.string.recognitionName)))
            processCommand(hypothesis.getHypstr());
    }

    private void processCommand(String command) {
        smart_home_core.CommandRequest message = commandClient.newMessage();
        message.setCommand(command);
        commandClient.call(message, new ServiceResponseListener<smart_home_core.CommandResponse>() {
            @Override
            public void onSuccess(smart_home_core.CommandResponse response) {
                std_msgs.String toSpeak = speakPublisher.newMessage();
                toSpeak.setData(response.getResponse());
                speakPublisher.publish(toSpeak);
            }

            @Override
            public void onFailure(RemoteException e) {
                Log.e("SpeechRecognitionNode", "error getting command response", e);
            }
        });
    }

    @Override
    public void onError(Exception e) {
        Log.e(CONTEXT, e.getClass().getSimpleName() + " " + e.getMessage());
    }

    @Override
    public void onTimeout() {
        Log.i(CONTEXT, "timeout");
        startListen();
    }

    @Override
    public void onShutdown(Node node) {
        super.onShutdown(node);
        Log.i(CONTEXT, "shutdown");
        recognizer.cancel();
        recognizer.shutdown();
    }
}
