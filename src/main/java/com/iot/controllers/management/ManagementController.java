package com.iot.controllers.management;
import com.iot.model.auth.AuthenticateModel;
import com.iot.model.constants.Endpoints;
import com.iot.model.service.DetailedDevice;
import com.iot.model.service.DeviceDefinition;
import com.iot.model.utils.AlertDialog;
import com.iot.model.utils.HttpClient;
import com.iot.model.utils.ServerResponse;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.apache.http.HttpStatus;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.List;
import java.util.Set;

import static com.iot.model.constants.Responses.Service.*;
import static com.iot.model.utils.AlertDialog.CustomAlert.*;


public class ManagementController extends Manager {
    private int tokenExpiredRequestsCounter = 0;
    @FXML
    private Button findDeviceBtn;
    @FXML
    private Button findDeviceServiceBarBtn;
    @FXML
    private Button submitChangingStateBtn;

    private void setOnCloseOperation() {
        new Thread(() -> {
            while(true) {
                try {
                    Stage stage = getThisStage();
                    if (stage != null) {
                        stage.setOnHidden(event -> disconnectSockets());
                        break;
                    }
                } catch (NullPointerException ignored) {}
            }
        }).start();
    }

    @FXML
    protected void initialize() {
        if (!AuthenticateModel.getInstance().getIsAuthorized()) {
            AlertDialog.alertOf(EXCEPTION, "Уведомление", YOU_ARE_NOT_LOGIN_IN);
            homeScene();
        }

        setButtonsReactionOnAction(List.of(findDeviceServiceBarBtn, findDeviceBtn, submitChangingStateBtn));

        setUpResolvingConnectionWebSocket();
        resolvingConnectionsWS.connect();

        userComboBox.setPromptText(AuthenticateModel.getInstance().getUserLogin());
        userComboBox.setItems(FXCollections.observableArrayList(settingsReset, changeUserData, exitFromProfileText));

        setUpListViewSettings();
        isArrayWaiting = true;
        HttpClient.execute (null, Endpoints.ALL_DEVICES, HttpClient.HttpMethods.GET);
        loadingCircle2.setVisible(true);
        checkServerResponseIs();

        setOnCloseOperation();
    }

    private void collectDevicesToList(JSONArray arr) {
        ObservableList<DeviceDefinition> devices = FXCollections.observableArrayList();

        short counter = 0;

        for (Object elem : arr) {
            JSONObject obj = (JSONObject) elem;
            devices.add(
                    new DeviceDefinition(
                            (long) obj.get("deviceId"),
                            obj.get("deviceName").toString(),
                            obj.get("deviceDescription").toString(),
                            obj.get("boardId").toString(),
                            ++counter
                    )
            );
        }

        introDeviceInfo.setItems(devices);
        loadingCircle2.setVisible(false);
    }

    @Override
    protected void transactServerResponse(ServerResponse response) {
        try {
            JSONParser parser = new JSONParser();

            switch (response.responseCode()) {
                case HttpStatus.SC_OK -> {
                    JSONObject resultObject = (JSONObject) parser.parse(response.responseMsg());
                    try {
                        String responseMessage = resultObject.get("msg").toString();

                        if (!responseMessage.equals(DEVICE_STATE_HAS_BEEN_UPDATED)
                            && !responseMessage.equals(DEVICE_LISTENING_STATE_WAS_RESET)) {
                            throw new RuntimeException("Unknown message");
                        }

                        String msg = switch (responseMessage) {
                            case DEVICE_STATE_HAS_BEEN_UPDATED -> SENSOR_STATE_UPDATED;
                            case DEVICE_LISTENING_STATE_WAS_RESET -> DEVICE_STATE_RESET;
                            default -> throw new RuntimeException("Unknown option");
                        };

                        Platform.runLater(() ->
                                AlertDialog.alertOf(INFORMATION, "Уведомление", msg).showAndWait()
                        );

                    } catch (Exception e) {
                        try {
                            if (resultObject.get("message").toString().equals(ACCESS_TOKEN_WAS_UPDATED)) {
                                tokenExpiredRequestsCounter = 0;
                                Platform.runLater(()-> {
                                    getThisStage().close();
                                    serviceUser();
                                });
                                return;
                            }
                        } catch(Exception ignore) {}

                        if (isArrayWaiting) {
                            Platform.runLater(()-> collectDevicesToList((JSONArray) resultObject.get("deviceListInfo")));
                        } else {
                            JSONObject obj = (JSONObject) resultObject.get("deviceInfo");
                            JSONObject sensors = (JSONObject) parser.parse(obj.get("sensorsState").toString());
                            Set<String> keys = sensors.keySet();

                            ObservableList<DetailedDevice> list = FXCollections.observableArrayList();
                            keys.forEach(key->
                                    list.add(new DetailedDevice(
                                            (long) obj.get("deviceId"),
                                            key,
                                            translateState(sensors.get(key).toString())
                                    )));

                            Platform.runLater(() -> {
                                sensorsList.setItems(list);
                                deviceName.setText(obj.get("deviceName").toString());
                                setFullDescPaneSettings();
                                setFullDescPaneVisible(true);
                            });
                        }
                    }

                }

                case HttpStatus.SC_INTERNAL_SERVER_ERROR -> {
                    JSONObject resultObject = (JSONObject) parser.parse(response.responseMsg());
                    String message = switch (resultObject.get("code").toString()) {
                        case "ET01" -> null;
                        case "EE02" -> SENSOR_WAS_NOT_FOUND;
                        case "EE04" -> USER_OR_DEVICE_WAS_NOT_FOUND;
                        case "EE06" -> DEVICE_STATE_WAS_NOT_UPDATED;
                        case "EO01" -> DEVICE_IS_NOT_LISTENING;
                        default -> throw new RuntimeException("Unknown error");
                    };

                    if (message == null) checkIsTokenExpired(tokenExpiredRequestsCounter);
                    else {
                        Platform.runLater(() ->
                                AlertDialog.alertOf(EXCEPTION, "Уведомление", message).showAndWait()
                        );
                    }
                }
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
