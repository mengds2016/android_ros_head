package com.github.chaosal.androidroshead;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.ros.message.MessageListener;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.Node;
import org.ros.node.topic.Subscriber;

import java.util.Locale;


public class SpeakNode extends AbstractNodeMain implements TextToSpeech.OnInitListener {

    public final static String TOPIC = "/speak";
    private TextToSpeech tts;
    private Subscriber<std_msgs.String> speakSubscriber;
    private Context context;
    private GlobalState globalState;

    public SpeakNode(Context applicationContext, GlobalState globalState) {
        this.context = applicationContext;
        this.globalState = globalState;
    }

    @Override
    public GraphName getDefaultNodeName() {
        return GraphName.of(context.getString(R.string.nodes_prefix) + TOPIC);
    }

    @Override
    public void onStart(ConnectedNode connectedNode) {
        super.onStart(connectedNode);
        speakSubscriber =
                connectedNode.newSubscriber(context.getString(R.string.nodes_prefix) + TOPIC, std_msgs.String._TYPE);
        tts = new TextToSpeech(context, this);
        tts.setLanguage(new Locale("ru"));
        globalState.setSpeakingStateResolver(new GlobalState.StateResolver<Boolean>() {
            @Override
            public Boolean getState() {
                return tts.isSpeaking();
            }
        });
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            //tts.speak(context.getString(R.string.hello), TextToSpeech.QUEUE_FLUSH, null);
            speakSubscriber.addMessageListener(new MessageListener<std_msgs.String>() {
                @Override
                public void onNewMessage(std_msgs.String string) {
                    if(!globalState.isListening()) {
                        globalState.setSpeaking(true);
                        tts.speak(string.getData(), TextToSpeech.QUEUE_FLUSH, null);
                    }
                }
            });
        } else
            Log.e(TOPIC, context.getString(R.string.error_tts_init));
    }

    @Override
    public void onShutdown(Node node) {
        super.onShutdown(node);
        speakSubscriber.shutdown();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    @Override
    public void onError(Node node, Throwable throwable) {
        super.onError(node, throwable);
        Log.e(TOPIC, throwable.getMessage(), throwable);
    }
}
