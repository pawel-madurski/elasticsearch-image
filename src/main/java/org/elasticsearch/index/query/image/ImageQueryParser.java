package org.elasticsearch.index.query.image;


import net.semanticmetadata.lire.imageanalysis.features.Extractor;
import net.semanticmetadata.lire.imageanalysis.features.LireFeature;
import net.semanticmetadata.lire.indexers.hashing.BitSampling;
import net.semanticmetadata.lire.indexers.hashing.LocalitySensitiveHashing;
import net.semanticmetadata.lire.utils.ImageUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchImageProcessException;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.get.GetField;
import org.elasticsearch.index.mapper.image.FeatureEnum;
import org.elasticsearch.index.mapper.image.HashEnum;
import org.elasticsearch.index.mapper.image.ImageMapper;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.query.QueryParser;
import org.elasticsearch.index.query.QueryParsingException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ImageQueryParser implements QueryParser {

    public static final String NAME = "image";

    private Client client;

    @Inject
    public ImageQueryParser(Client client) {
        this.client = client;
    }

    @Override
    public String[] names() {
        return new String[] {NAME};
    }

    @Override
    public Query parse(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        XContentParser.Token token = parser.nextToken();
     
        if (token != XContentParser.Token.FIELD_NAME) {
            throw new QueryParsingException(parseContext, "[image] query malformed, no field");
        }

        String fieldName = parser.currentName();
        FeatureEnum featureEnum = null;
        byte[] image = null;
        HashEnum hashEnum = null;
        float boost = 1.0f;
        int limit = -1;

        String lookupIndex = parseContext.index().name();
        String lookupType = null;
        String lookupId = null;
        String lookupPath = null;
        String lookupRouting = null;


        token = parser.nextToken();
        if (token == XContentParser.Token.START_OBJECT) {
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else {
                    if ("feature".equals(currentFieldName)) {
                        featureEnum = FeatureEnum.getByName(parser.text());
                    } else if ("image".equals(currentFieldName)) {
                        image = parser.binaryValue();
                    } else if ("hash".equals(currentFieldName)) {
                        hashEnum = HashEnum.getByName(parser.text());
                    } else if ("boost".equals(currentFieldName)) {
                        boost = parser.floatValue();
                    } else if ("limit".equals(currentFieldName)) {
                        limit = parser.intValue();
                    }else if ("index".equals(currentFieldName)) {
                        lookupIndex = parser.text();
                    } else if ("type".equals(currentFieldName)) {
                        lookupType = parser.text();
                    } else if ("id".equals(currentFieldName)) {
                        lookupId = parser.text();
                    } else if ("path".equals(currentFieldName)) {
                        lookupPath = parser.text();
                    } else if ("routing".equals(currentFieldName)) {
                        lookupRouting = parser.textOrNull();
                    } else {
                        throw new QueryParsingException(parseContext, "[image] query does not support [" + currentFieldName + "]");
                    }
                }
            }
            parser.nextToken();
        }

        if (featureEnum == null) {
            throw new QueryParsingException(parseContext, "No feature specified for image query");
        }

        String luceneFieldName = fieldName + "." + featureEnum.name();
        LireFeature lireFeature = null;

        if (image != null) {
            try {
                lireFeature = featureEnum.getFeatureClass().newInstance();
                BufferedImage img = ImageIO.read(new ByteBufferStreamInput(ByteBuffer.wrap(image)));
                if (Math.max(img.getHeight(), img.getWidth()) > ImageMapper.MAX_IMAGE_DIMENSION) {
                    img = ImageUtils.scaleImage(img, ImageMapper.MAX_IMAGE_DIMENSION);
                }
                ((Extractor)lireFeature).extract(img);
            } catch (Exception e) {
                throw new ElasticsearchImageProcessException("Failed to parse image", e);
            }
        } else if (lookupIndex != null && lookupType != null && lookupId != null && lookupPath != null) {

            String lookupFieldName = lookupPath + "." + featureEnum.name();

            GetResponse getResponse = client.get(new GetRequest(lookupIndex, lookupType, lookupId).preference("_local").routing(lookupRouting).fields(lookupFieldName).realtime(false)).actionGet();
            if (getResponse.isExists()) {
                GetField getField = getResponse.getField(lookupFieldName);
                if (getField != null) {
                    BytesRef bytesReference = (BytesRef) getField.getValue();
                    try {
                        lireFeature = featureEnum.getFeatureClass().newInstance();

                        lireFeature.setByteArrayRepresentation(bytesReference.bytes);
                    } catch (Exception e) {
                        throw new ElasticsearchImageProcessException("Failed to parse image", e);
                    }
                }
            }
        }
        
        if (lireFeature == null) {
            throw new QueryParsingException(parseContext, "No feature found for image query");
        }

        if (hashEnum == null) {  // no hash, need to scan all documents
            return new ImageQuery(luceneFieldName, lireFeature, boost);
        } else {  // query by hash first
            int[] hash = null;
            if (hashEnum.equals(HashEnum.BIT_SAMPLING)) {
                hash = BitSampling.generateHashes(lireFeature.getFeatureVector());
            } else if (hashEnum.equals(HashEnum.LSH)) {
                hash = LocalitySensitiveHashing.generateHashes(lireFeature.getFeatureVector());
            }
            String hashFieldName = luceneFieldName + "." + ImageMapper.HASH + "." + hashEnum.name();

            if (limit > 0) {  // has max result limit, use ImageHashLimitQuery
                return new ImageHashLimitQuery(hashFieldName, hash, limit, luceneFieldName, lireFeature, boost);
            } else {  // no max result limit, use ImageHashQuery
                BooleanQuery.Builder builder=new BooleanQuery.Builder()
                        .setDisableCoord(true);

                ImageScoreCache imageScoreCache = new ImageScoreCache();

                for (int h : hash) {
                    builder.add(new BooleanClause(
                            new ImageHashQuery(
                                    new Term(hashFieldName, Integer.toString(h)),
                                    luceneFieldName,
                                    lireFeature,
                                    imageScoreCache,
                                    boost),
                            BooleanClause.Occur.SHOULD));
                }
                return builder.build();
            }

        }
    }
}
