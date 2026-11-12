package store.dao.database;

public interface IParamStringStringDAO {
    void putNoOverwrite(String parameter, String value);
    void put(String parameter, String value);
    String getValue (String parameter);
    boolean isExistValueByParameter (String parameter);
}
